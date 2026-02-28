package example.websocket.audio.service;

import java.util.*;
import java.util.function.Consumer;

import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEvent;
import com.coze.openapi.client.chat.model.ChatEventType;
import com.coze.openapi.client.connversations.CreateConversationReq;
import com.coze.openapi.client.connversations.CreateConversationResp;
import com.coze.openapi.client.connversations.message.model.Message;
import com.coze.openapi.client.exception.CozeApiException;
import com.coze.openapi.service.service.CozeAPI;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class ChatService {
  private final CozeAPI coze;
  private final String botId;
  private final String userId;
  private final String model;
  private String conversationId;
  private final List<Message> messageHistory = new ArrayList<>();
  private volatile String currentChatId = null;
  private volatile boolean isWaitingForResponse = false;
  private Disposable currentChatDisposable = null;
  // 用于累积合并的用户消息
  private StringBuilder accumulatedUserMessage = new StringBuilder();

  public ChatService(CozeAPI coze, String botId, String userId, String model) {
    this.coze = coze;
    this.botId = botId;
    this.userId = userId;
    this.model = model;
  }

  public void createConversation() throws Exception {
    System.out.println("[ChatService] 开始创建会话, BotID: " + botId);
    CreateConversationReq req = new CreateConversationReq();
    req.setBotID(botId);

    CreateConversationResp resp = coze.conversations().create(req);
    conversationId = resp.getConversation().getId();
    System.out.println("[ChatService] 会话创建成功, ConversationID: " + conversationId);
  }

  public void createConversation(String customConversationId) {
    this.conversationId = customConversationId;
    System.out.println("[ChatService] 使用自定义会话ID: " + conversationId);
  }

  public synchronized void sendMessage(
      String text,
      Consumer<String> onResponseStart,
      Consumer<String> onResponseDelta,
      Consumer<String> onResponseComplete,
      Consumer<Exception> onError) {

    // 如果正在等待 Bot 响应，追加到累积消息
    if (isWaitingForResponse) {
      if (accumulatedUserMessage.length() > 0) {
        accumulatedUserMessage.append(" ");
      }
      accumulatedUserMessage.append(text);
      System.out.println("[ChatService] 追加到累积消息，当前内容: " + accumulatedUserMessage.toString());

      // 取消当前的 Chat 请求
      if (currentChatDisposable != null && !currentChatDisposable.isDisposed()) {
        currentChatDisposable.dispose();
        System.out.println("[ChatService] 取消当前请求，重新发送合并后的消息");
      }

      // 从历史记录中删除最后一条用户消息（如果存在）
      if (!messageHistory.isEmpty()) {
        Message lastMsg = messageHistory.get(messageHistory.size() - 1);
        if (lastMsg.getRole().getValue().equals("user")) {
          messageHistory.remove(messageHistory.size() - 1);
          System.out.println("[ChatService] 删除历史记录中的上一条用户消息");
        }
      }

      // 使用累积的完整消息重新发送（不清空accumulatedUserMessage，继续累积）
      String mergedText = accumulatedUserMessage.toString();
      isWaitingForResponse = false;
      sendMessageInternal(
          mergedText, onResponseStart, onResponseDelta, onResponseComplete, onError);
      return;
    }

    // 正常流程：开始新的请求，清空累积消息并设置新内容
    accumulatedUserMessage.setLength(0);
    accumulatedUserMessage.append(text);
    sendMessageInternal(text, onResponseStart, onResponseDelta, onResponseComplete, onError);
  }

  private void sendMessageInternal(
      String text,
      Consumer<String> onResponseStart,
      Consumer<String> onResponseDelta,
      Consumer<String> onResponseComplete,
      Consumer<Exception> onError) {

    // Remove duplicate user message
    if (!messageHistory.isEmpty()) {
      Message lastMsg = messageHistory.get(messageHistory.size() - 1);
      if (lastMsg.getRole().getValue().equals("user")) {
        messageHistory.remove(messageHistory.size() - 1);
      }
    }

    // Add user message
    Message userMessage = Message.buildUserQuestionText(text);
    messageHistory.add(userMessage);

    // Build history JSON
    String historyJson = buildHistoryJson();
    // 打印历史记录条数和最后一条user消息
    int userMsgCount = 0;
    String lastUserContent = "";
    for (Message msg : messageHistory) {
      if ("user".equals(msg.getRole().getValue())) {
        userMsgCount++;
        lastUserContent = msg.getContent();
      }
    }
    System.out.println(
        "[ChatService] 历史记录共 "
            + messageHistory.size()
            + " 条, user消息 "
            + userMsgCount
            + " 条, 最后一条user: "
            + lastUserContent.substring(0, Math.min(30, lastUserContent.length()))
            + "...");
    Message messageWithHistory = Message.buildUserQuestionText(historyJson);

    Map<String, Object> parameters = new HashMap<>();
    if (model != null && !model.isEmpty()) {
      parameters.put("model", model);
      System.out.println("[ChatService] 使用模型: " + model);
    } else {
      System.out.println("[ChatService] 使用默认模型");
    }

    CreateChatReq chatReq =
        CreateChatReq.builder()
            .botID(botId)
            .userID(userId)
            .messages(Collections.singletonList(messageWithHistory))
            .parameters(parameters)
            .build();

    StringBuilder responseBuilder = new StringBuilder();
    isWaitingForResponse = true;

    Flowable<ChatEvent> chatResp = coze.chat().stream(chatReq);

    currentChatDisposable =
        chatResp
            .subscribeOn(Schedulers.io())
            .subscribe(
                event ->
                    handleChatEvent(
                        event,
                        responseBuilder,
                        onResponseStart,
                        onResponseDelta,
                        onResponseComplete,
                        onError),
                throwable -> {
                  isWaitingForResponse = false;
                  handleError(throwable, onError);
                },
                () -> {
                  // onComplete
                });
  }

  private void handleChatEvent(
      ChatEvent event,
      StringBuilder responseBuilder,
      Consumer<String> onResponseStart,
      Consumer<String> onResponseDelta,
      Consumer<String> onResponseComplete,
      Consumer<Exception> onError) {
    String eventValue = event.getEvent().getValue();

    if (ChatEventType.CONVERSATION_CHAT_CREATED.getValue().equals(eventValue)) {
      if (event.getLogID() != null) {
        currentChatId = event.getLogID();
        if (onResponseStart != null) {
          onResponseStart.accept(currentChatId);
        }
      }
    } else if (ChatEventType.CONVERSATION_MESSAGE_DELTA.getValue().equals(eventValue)) {
      if (event.getMessage() != null && event.getMessage().getContent() != null) {
        String content = event.getMessage().getContent();
        responseBuilder.append(content);
        if (onResponseDelta != null) {
          onResponseDelta.accept(content);
        }
      }
    } else if (ChatEventType.CONVERSATION_CHAT_COMPLETED.getValue().equals(eventValue)) {
      String response = responseBuilder.toString();
      isWaitingForResponse = false;
      // Bot响应完成后，清空累积消息
      accumulatedUserMessage.setLength(0);

      // Add AI response to history
      Message aiMessage = Message.buildAssistantAnswer(response);
      messageHistory.add(aiMessage);

      currentChatId = null;

      if (onResponseComplete != null) {
        onResponseComplete.accept(response);
      }
    } else if ("conversation.chat.failed".equals(eventValue)) {
      isWaitingForResponse = false;
      String errorMsg = "未知错误";
      if (event.getMessage() != null && event.getMessage().getContent() != null) {
        errorMsg = event.getMessage().getContent();
      }
      System.err.println("[ChatService] Chat 失败: " + errorMsg);
      System.err.println("[ChatService] 失败事件详情: " + event);
      if (onError != null) {
        onError.accept(new RuntimeException("Chat failed: " + errorMsg));
      }
    }
  }

  private void handleError(Throwable throwable, Consumer<Exception> onError) {
    System.err.println("[ChatService] 错误: " + throwable.getMessage());
    if (throwable instanceof CozeApiException) {
      CozeApiException apiException = (CozeApiException) throwable;
      System.err.println(
          "[ChatService] API错误: " + apiException.getCode() + ", " + apiException.getMsg());
    }
    if (onError != null) {
      onError.accept((Exception) throwable);
    }
  }

  private String buildHistoryJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < messageHistory.size(); i++) {
      Message msg = messageHistory.get(i);
      if (i > 0) sb.append(",");
      sb.append(
          String.format(
              "{\"role\":\"%s\",\"content\":\"%s\"}",
              msg.getRole().getValue(),
              msg.getContent().replace("\"", "\\\"").replace("\n", "\\n")));
    }
    sb.append("]");
    return sb.toString();
  }

  public String getCurrentChatId() {
    return currentChatId;
  }

  public boolean isWaitingForResponse() {
    return isWaitingForResponse;
  }

  public void clearHistory() {
    messageHistory.clear();
    accumulatedUserMessage.setLength(0);
    isWaitingForResponse = false;
  }

  public List<Message> getMessageHistory() {
    return new ArrayList<>(messageHistory);
  }
}
