/* eslint-disable @typescript-eslint/no-explicit-any, max-lines */
import { useRef, useState, useEffect } from 'react';

import './index.css';
import {
  Button,
  message,
  Select,
  Modal,
  Layout,
  Row,
  Col,
  Card,
  Typography,
  Slider,
  Tooltip,
} from 'antd';
import {
  WsToolsUtils,
  WsTranscriptionClient,
  WsSpeechClient,
} from '@coze/api/ws-tools';
import { type CommonErrorEvent, WebsocketsEventType } from '@coze/api';
import { AudioOutlined, SoundOutlined, SoundFilled } from '@ant-design/icons';

import getConfig from '../../utils/config';
import Settings from '../../components/settings2';
import EventInput from '../../components/event-input';
import { ConsoleLog } from '../../components/console-log';
import {
  AudioConfig,
  type AudioConfigRef,
} from '../../components/audio-config';
import SentenceMessage, { type SentenceMessageRef } from './sentence-message';
import SendMessage from './send-message';
import ReceiveMessage from './receive-message';
import Operation from './operation';

const { Paragraph, Text } = Typography;
const localStorageKey = 'realtime-quickstart-ws';
const config = getConfig(localStorageKey);

// Helper function to get chatUpdate config based on turn detection mode
const getChatUpdateConfig = (turnDetectionType: string) => ({
  data: {
    input_audio: {
      format: 'pcm',
      codec: 'pcm',
      sample_rate: 48000,
    },
    output_audio: {
      codec: 'pcm',
      pcm_config: {
        sample_rate: 24000,
      },
      voice_id: config.getVoiceId(),
    },
    turn_detection: {
      type: turnDetectionType,
    },
    need_play_prologue: true,
  },
});

// 获取回复模式配置
const getReplyMode = (): 'stream' | 'sentence' =>
  localStorage.getItem('replyMode') === 'sentence' ? 'sentence' : 'stream';

function Chat() {
  const transcriptionClientRef = useRef<WsTranscriptionClient>();
  const speechClientRef = useRef<WsSpeechClient>();
  const audioConfigRef = useRef<AudioConfigRef>(null);
  const sentenceMessageRef = useRef<SentenceMessageRef>(null);
  const lastUserMessageRef = useRef<string>('');
  const currentChatIdRef = useRef<string>('');
  // messages and audio list
  const [messageList, setMessageList] = useState<{
    content: string;
    type: 'user' | 'ai';
    timestamp: number;
  }[]>([]);
  const [audioList, setAudioList] = useState<{ label: string; url: string }[]>([]);
  const lastTranscriptRef = useRef('');
  const isInterimRef = useRef(false);
  const synthDebounceTimer = useRef<NodeJS.Timeout | null>(null);
  const wasRecordingBeforeSynthRef = useRef(false);
  // 是否正在连接
  const [isConnecting, setIsConnecting] = useState(false);
  // 是否已连接
  const [isConnected, setIsConnected] = useState(false);
  const [transcript, setTranscript] = useState('');
  const isListeningRef = useRef(false);

  const [replyMode, setReplyMode] = useState<'stream' | 'sentence'>(
    getReplyMode(),
  );

  const [inputDevices, setInputDevices] = useState<MediaDeviceInfo[]>([]);
  const [selectedInputDevice, setSelectedInputDevice] = useState<string>('');

  // 添加控制弹窗显示的状态
  const [isConfigModalOpen, setIsConfigModalOpen] = useState(false);
  const isMobile = WsToolsUtils.isMobile();

  // 按键说话状态
  const [isPressRecording, setIsPressRecording] = useState(false);
  const [recordingDuration, setRecordingDuration] = useState(0);
  const recordTimer = useRef<NodeJS.Timeout | null>(null);
  const maxRecordingTime = 60; // 最大录音时长（秒）
  const [isCancelRecording, setIsCancelRecording] = useState(false);
  const startTouchY = useRef<number>(0);
  const [isMuted, setIsMuted] = useState(false);
  // 音量控制 (0-100)
  const [volume, setVolume] = useState(100);
  // 获取对话模式
  const [turnDetectionType, setTurnDetectionType] = useState('server_vad');
  // 会话ID，用于保持对话上下文
  const [conversationId, setConversationId] = useState<string>('');

  useEffect(() => {
    const getDevices = async () => {
      const devices = await WsToolsUtils.getAudioDevices();
      setInputDevices(devices.audioInputs);
      if (devices.audioInputs.length > 0) {
        setSelectedInputDevice(devices.audioInputs[0].deviceId);
      }
    };

    getDevices();
  }, []);

  // cleanup on unmount
  useEffect(() => {
    return () => {
      if (recordTimer.current) {
        clearInterval(recordTimer.current);
      }
      transcriptionClientRef.current?.destroy?.();
      speechClientRef.current?.disconnect?.();
    };
  }, []);

  async function initClients() {
    const permission = await WsToolsUtils.checkDevicePermission();
    if (!permission.audio) {
      throw new Error('需要麦克风访问权限');
    }

    if (!config.getPat()) {
      throw new Error('请先配置个人访问令牌 -> 右上角 Settings');
    }

    if (!config.getBotId()) {
      throw new Error('请先配置智能体ID -> 右上角 Settings');
    }

    const audioConfig = audioConfigRef.current?.getSettings();
    console.log('audioConfig', audioConfig);

    if (!config.getPat()) {
      throw new Error('请先配置个人访问令牌 -> 右上角 Settings');
    }

    // transcription client
    const tClient = new WsTranscriptionClient({
      token: config.getPat(),
      baseWsURL: config.getBaseWsUrl(),
      allowPersonalAccessTokenInBrowser: true,
      debug: audioConfig?.debug,
      deviceId: selectedInputDevice || undefined,
      aiDenoisingConfig: !audioConfig?.noiseSuppression
        ? {
            mode: audioConfig?.denoiseMode,
            level: audioConfig?.denoiseLevel,
            assetsPath:
              'https://lf3-static.bytednsdoc.com/obj/eden-cn/613eh7lpqvhpeuloz/websocket',
          }
        : undefined,
      audioCaptureConfig: {
        echoCancellation: audioConfig?.echoCancellation,
        noiseSuppression: audioConfig?.noiseSuppression,
        autoGainControl: audioConfig?.autoGainControl,
      },
      wavRecordConfig: {
        enableSourceRecord: false,
        enableDenoiseRecord: false,
      },
    });

    // transcription events
    tClient.on(WebsocketsEventType.ALL, event => {
      console.debug('[transcription] ALL event', event);
    });
    tClient.on(WebsocketsEventType.TRANSCRIPTIONS_MESSAGE_UPDATE, async event => {
      try {
        // @ts-ignore
        const content = (event as any).data?.content || '';
        lastTranscriptRef.current = content;
        setTranscript(content);
        console.debug('[transcription] interim:', content);

        // 接收到语音识别结果更新时停止播放录音
        if (speechClientRef.current) {
          await speechClientRef.current.interrupt().catch(err => {
            console.error('[speech] interrupt error', err);
          });
        }

        // 不再更新messageList，只在clear之前推送

        // debounce synthesize: wait for a pause in interim updates
        if (synthDebounceTimer.current) {
          clearTimeout(synthDebounceTimer.current);
        }
        synthDebounceTimer.current = setTimeout(async () => {
          synthDebounceTimer.current = null;
          // mark interim as final and synthesize
          isInterimRef.current = false;
          const text = lastTranscriptRef.current || '';
          if (text) {
            try {
              // 不暂停转录，保持麦克风持续输入避免超时
              wasRecordingBeforeSynthRef.current = false;

              // 推送用户消息到对话栏
              setMessageList(prev => {
                const lastMessage = prev[prev.length - 1];
                if (lastMessage && lastMessage.content === text) {
                  console.debug('[transcription] skip duplicate user message:', text);
                  return prev;
                }
                return [
                  ...prev,
                  { content: text, type: 'user', timestamp: Date.now() },
                ];
              });

              // 发送clear指令清除转录缓冲区
              try {
                // @ts-ignore
                await transcriptionClientRef.current?.ws?.send({
                  id: `clear_${Date.now()}`,
                  event_type: 'input_audio_buffer.clear'
                });
                console.debug('[transcription] sent clear command before callbot request');

                // 清除本地转录缓存
                lastTranscriptRef.current = '';
                setTranscript('');
              } catch (e) {
                console.error('[transcription] send clear command error', e);
              }

              await callBot(text);
            } catch (e) {
              console.error('debounced synth error', e);
            } finally {
              // 不需要恢复转录，因为没有暂停
              wasRecordingBeforeSynthRef.current = false;
            }
          }
        }, 1200);
      } catch (e) {
        console.warn(e);
      }
    });
    // tClient.on(WebsocketsEventType.TRANSCRIPTIONS_MESSAGE_COMPLETED, async () => {
    //   const content = lastTranscriptRef.current || '';
    //   console.debug('[transcription] completed:', content);
    //   if (content) {
    //     // 不再推送messageList，只调用callBot（会在clear之前推送）
    //     try {
    //       await callBot(content);
    //     } catch (err) {
    //       console.error('call bot error after transcription completed', err);
    //     }
    //   }
    //   setTranscript('');
    //   lastTranscriptRef.current = '';

    //   // if continuous listening is enabled, restart transcription after synthesis/playback
    //   if (isListeningRef.current) {
    //     try {
    //       // cleanup previous client
    //       transcriptionClientRef.current?.destroy?.();
    //     } catch (e) {
    //       console.warn('destroy transcription client error', e);
    //     }
    //     // re-init and start a fresh transcription client
    //     try {
    //       await initClients();
    //       await transcriptionClientRef.current?.start();
    //       console.debug('[transcription] restarted listening');
    //     } catch (e) {
    //       console.error('[transcription] restart error', e);
    //     }
    //   }
    // });
    // 监听bot回复
    tClient.on('message.completed', (ev: any) => {
      try {
        const botReply = ev.data?.content || '';
        if (botReply) {
          console.debug('[bot] reply received:', botReply);
          // 合成语音
          synthesizeText(botReply);
        }
      } catch (error) {
        console.error('[bot] handle reply error', error);
      }
    });
    tClient.on(WebsocketsEventType.TRANSCRIPTIONS_CREATED, ev => {
      console.debug('[transcription] created', ev);
    });
    tClient.on(WebsocketsEventType.ERROR, (err: unknown) => {
      console.error('[transcription] error', err);
      message.error((err as CommonErrorEvent)?.data?.msg || '识别错误');
    });

    transcriptionClientRef.current = tClient;

    // speech client will be lazily created when synthesis is requested
  }

  // message/event handlers are registered during initClients

  // 创建会话（通过HTTP API）
  const createConversation = async () => {
    try {
      // 生成固定的user_id，用于标识当前用户
      const userId = `whp`;
      localStorage.setItem('coze_user_id', userId);

      // 调用v1/conversation/create接口创建会话
      const response = await fetch(`https://api.coze.cn/v1/conversation/create`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${config.getPat()}`
        },
        body: JSON.stringify({
          bot_id: config.getBotId(),
          name: '推销保险'
        })
      });
      const data = await response.json();
      if (data.code === 0) {
        const newConversationId = data.data.id;
        setConversationId(newConversationId);
        localStorage.setItem('coze_conversation_id', newConversationId);
        console.debug('[conversation] created:', newConversationId);
      } else {
        throw new Error(data.msg || '创建会话失败');
      }
    } catch (error) {
      console.error('[conversation] create error', error);
      message.error(`创建会话失败：${(error as Error).message}`);
    }
  };

  const handleConnect = async () => {
    try {
      if (!transcriptionClientRef.current) {
        await initClients();
      }
      setReplyMode(getReplyMode());
      // 创建会话
      await createConversation();
      // Connected for purposes of enabling UI - transcription will start when user records
      setIsConnected(true);
      // enable continuous listening mode
      isListeningRef.current = true;
      try {
        await transcriptionClientRef.current?.start();
        console.debug('[transcription] started listening');
      } catch (e) {
        console.error('[transcription] start error', e);
      }
    } catch (error) {
      console.error(error);
      message.error(`连接错误：${(error as Error).message}`);
    }
  };

  // 调用bot智能体（通过HTTP API）
  async function callBot(text: string) {
    if (!text) return;
    try {
      // 停止播放当前语音
      try {
        await speechClientRef.current?.interrupt();
        console.debug('[speech] interrupted playback');
      } catch (e) {
        console.warn('[speech] interrupt error', e);
      }


      // 如果有正在进行的对话，先取消
      const currentChatId = currentChatIdRef.current;
      const savedConversationId = localStorage.getItem('coze_conversation_id') || conversationId;

      if (currentChatId && savedConversationId) {
        try {
          const cancelResponse = await fetch(`https://api.coze.cn/v3/chat/cancel`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'Authorization': `Bearer ${config.getPat()}`
            },
            body: JSON.stringify({
              chat_id: currentChatId,
              conversation_id: savedConversationId
            })
          });
          const cancelData = await cancelResponse.json();
          if (cancelData.code === 0) {
            console.debug('[bot] canceled previous chat:', currentChatId, lastUserMessageRef.current);
            // 将前一次的请求文本拼入当前的对话请求
            text = `${lastUserMessageRef.current} ${text}`.trim();
          } else {
            console.warn('[bot] cancel failed:', cancelData.msg);
          }
        } catch (e) {
          console.debug('[bot] cancel skipped:', e);
        } finally {
          currentChatIdRef.current = '';
        }
      }
      lastUserMessageRef.current = text;



      // 获取之前保存的user_id
      const userId = localStorage.getItem('coze_user_id');

      if (!savedConversationId) {
        console.debug('conversationId为空', conversationId);
        console.debug('localStorage coze_conversation_id:', localStorage.getItem('coze_conversation_id'));
      }

      console.debug('[bot] calling with text:', text, 'conversationId:', savedConversationId);

      // 调用发起对话接口

      // const response = await fetch(`https://teresia-unsocialising-hellen.ngrok-free.dev/v3/chat?conversation_id=7374752000116113452`, {
      //   method: 'POST',
      //   headers: {
      //     'Content-Type': 'application/json',
      //     'Authorization': `Bearer pat_test_token`
      //   },
      //   body: JSON.stringify({
      //     bot_id: "734829333445931****",
      //     user_id: "123456789",
      //     stream: true,
      //     auto_save_history: true,
      //     additional_messages: [
      //       {
      //         role: 'user',
      //         content_type: 'text',
      //         content: '2024年10月1日是星期几'
      //       }
      //     ]
      //   })
      // });

       const response = await fetch(`https://api.coze.cn/v3/chat?conversation_id=${savedConversationId}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${config.getPat()}`
        },
        body: JSON.stringify({
          bot_id: config.getBotId(),
          user_id: userId,
          stream: true,
          auto_save_history: true,
          additional_messages: [
            {
              role: 'user',
              type: 'question',
              content_type: 'text',
              content: text
            }
          ]
        })
      });

      // 处理流式响应
      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error('Failed to get response reader');
      }

      const decoder = new TextDecoder();
      let botReply = '';
      let currentEvent = '';
      let callBotChatId = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value);
        const lines = chunk.split('\n').filter(line => line.trim());

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
            console.debug('[bot] event:', currentEvent);
          } else if (line.startsWith('data:')) {
            try {
              const data = JSON.parse(line.slice(5));
              console.debug('[bot] raw data:', data);

              // 保存chat_id（Coze API可能不会发送conversation.chat.created事件，从第一个消息中提取）
              if (currentEvent === 'conversation.chat.created' && data.id) {
                currentChatIdRef.current = data.id;
                callBotChatId = data.id;
                console.debug('[bot] saved chat_id from created event:', data.id);
              } else if (!currentChatIdRef.current && data.id) {
                currentChatIdRef.current = data.id;
                callBotChatId = data.id;
                console.debug('[bot] saved chat_id from message:', data.id);
              }

              // 根据当前event类型处理data
              if (currentEvent === 'conversation.message.delta') {
                // 只处理type为answer的delta消息
                if (data.type === 'answer') {
                  // 判断callBotChatId与currentChatIdRef是否一致
                  if (callBotChatId !== currentChatIdRef.current) {
                    console.debug('[bot] skip processing delta: callBotChatId not match currentChatIdRef');
                    return;
                  }else{
                    currentChatIdRef.current = "";
                  }

                  const deltaContent = data.content || '';
                  botReply += deltaContent;
                  // console.debug('[bot] delta:', deltaContent, 'current reply:', botReply);
                }
              } else if (currentEvent === 'conversation.message.completed') {
                // 只处理type为answer的completed消息
                if (data.type === 'answer') {
                  // 判断callBotChatId与currentChatIdRef是否一致
                  if (callBotChatId !== currentChatIdRef.current) {
                    console.debug('[bot] skip processing: callBotChatId not match currentChatIdRef');
                    return;
                  }

                  const completedContent = data.content || '';
                  botReply = completedContent;
                  console.debug('[bot] completed:', botReply);
                  // 合成语音
                  if (botReply) {
                    console.debug('[bot] calling synthesizeText with:', botReply);
                    await synthesizeText(botReply);
                  } else {
                    console.error('[bot] empty reply content');
                  }
                } else {
                  console.debug('[bot] skipping non-answer message:', data.type);
                }

                currentChatIdRef.current = '';
                console.debug('[bot] cleared chat_id after workflow');
              }
            } catch (error) {
              console.error('[bot] parse error', error);
            }
          }
        }
      }

    } catch (error) {
      console.error('[bot] call error', error);
      message.error(`调用bot失败：${(error as Error).message}`);
    }
  }

  // synthesize text via speech websocket and play
  async function synthesizeText(text: string) {
    if (!text) return;
    try {
      if (!speechClientRef.current) {
        speechClientRef.current = new WsSpeechClient({
          token: config.getPat(),
          baseWsURL: config.getBaseWsUrl(),
          allowPersonalAccessTokenInBrowser: true,
        });

        speechClientRef.current.on('completed', () => {
          // playback completed
        });
        speechClientRef.current.on('data', ev => {
          // debug
          // console.debug('speech event', ev);
        });
      }

      await speechClientRef.current.connect({ voiceId: config.getVoiceId() });

      // 解析语气和内容
      const toneMatch = text.match(/\[(.*?)\]/);
      let tone = '';
      let content = text;

      if (toneMatch) {
        tone = toneMatch[1];
        content = text.replace(/\[(.*?)\]/, '').trim();
      }

      console.debug('[speech] tone:', tone, 'content:', content);

      // 如果有语气参数，更新语音合成配置
      if (tone) {
        try {
          await speechClientRef.current?.ws?.send({
            id: `speech_update_${Date.now()}`,
            event_type: 'speech.update',
            data: {
              output_audio: {
                context_texts: tone
              }
            }
          });
          console.debug('[speech] updated with tone:', tone);
        } catch (e) {
          console.error('[speech] update error', e);
        }
      }

      // For this flow we play streaming audio automatically via SDK player.
      // We will not create a replay blob — only show synthesized text in UI.
      speechClientRef.current.on(WebsocketsEventType.SPEECH_AUDIO_UPDATE, ev => {
        // optional debug
        // console.debug('[speech] audio update', ev);
      });
      speechClientRef.current.on(WebsocketsEventType.SPEECH_AUDIO_COMPLETED, ev => {
        console.debug('[speech] audio completed', ev);
      });

      // Show synthesized text in conversation as AI me    ssage
      setMessageList(prev => [
        ...prev,
        { content: content,     type: 'ai', timestamp: Date.now() },
      ]);

      // stream text and complete (player will play automatically)
      const playbackCompleted = new Promise<void>((resolve, reject) => {
        const onCompleted = () => {
          try {
            // remove listener
            speechClientRef.current?.off('completed', onCompleted as any);
          } catch (e) {
            // ignore
          }
          resolve();
        };
        speechClientRef.current?.on('completed', onCompleted as any);
        // a safety timeout in case completed doesn't fire
        setTimeout(() => {
          resolve();
        }, 30000);
      });

      await speechClientRef.current.appendAndComplete(content);
      await playbackCompleted;
    } catch (error) {
      console.error('synthesize error', error);
      message.error(`合成错误：${(error as Error).message || error}`);
    }
  }

  const handleSendText = async (text: string) => {
    if (!text) return;
    // add user message
    setMessageList(prev => [
      ...prev,
      { content: text, type: 'user', timestamp: Date.now() },
    ]);
    // synthesize and play
    await synthesizeText(text);
  };

  const handleSetAudioInputDevice = async (deviceId: string) => {
    try {
      // Set device for next transcription recordings
      setSelectedInputDevice(deviceId);
      setSelectedInputDevice(deviceId);
    } catch (error) {
      message.error(`设置音频输入设备失败：${error}`);
    }
  };

  // 添加设置变更处理函数
  function handleSettingsChange() {
    console.log('Settings changed');
    // 重新读取对话模式
    window.location.reload(); // 简单处理：刷新页面应用新设置
  }

  // 处理音量变化
  function handleVolumeChange(value: number) {
    setVolume(value);
  }

  // 处理按住说话按钮
  const handleVoiceButtonMouseDown = (
    e: React.MouseEvent | React.TouchEvent,
  ) => {
    if (isConnected && turnDetectionType === 'client_interrupt') {
      startPressRecord(e);
    }
  };

  const handleVoiceButtonMouseUp = () => {
    if (isPressRecording && !isCancelRecording) {
      finishPressRecord();
    } else if (isPressRecording && isCancelRecording) {
      cancelPressRecord();
    }
  };

  const handleVoiceButtonMouseLeave = () => {
    if (isPressRecording) {
      cancelPressRecord();
    }
  };

  const handleVoiceButtonMouseMove = (
    e: React.MouseEvent | React.TouchEvent,
  ) => {
    if (isPressRecording && startTouchY.current) {
      // 上滑超过50px则取消发送
      const clientY =
        'touches' in e ? e.touches[0].clientY : (e as React.MouseEvent).clientY;
      if (clientY < startTouchY.current - 50) {
        setIsCancelRecording(true);
      } else {
        setIsCancelRecording(false);
      }
    }
  };

  // 开始按键录音
  const startPressRecord = async (e: React.MouseEvent | React.TouchEvent) => {
    if (isConnected) {
      try {
        // 重置录音状态
        setIsPressRecording(true);
        setRecordingDuration(0);
        setIsCancelRecording(false);
        // Store initial touch position for determining sliding direction
        if ('clientY' in e) {
          startTouchY.current = (e as React.MouseEvent).clientY;
        } else if ('touches' in e && e.touches.length > 0) {
          startTouchY.current = e.touches[0].clientY;
        } else {
          startTouchY.current = 0;
        }

        // 开始录音 (transcription client)
        await transcriptionClientRef.current?.start();

        // 开始计时
        recordTimer.current = setInterval(() => {
          setRecordingDuration(prev => {
            const newDuration = prev + 1;
            // 超过最大录音时长自动结束
            if (newDuration >= maxRecordingTime) {
              finishPressRecord();
            }
            return newDuration;
          });
        }, 200);
      } catch (error: any) {
        message.error(`开始录音错误: ${error.message || '未知错误'}`);
        console.trace('开始录音错误:', error);
        // Clean up timer if it was set
        if (recordTimer.current) {
          clearInterval(recordTimer.current);
          recordTimer.current = null;
        }
        // Reset recording state
        setIsPressRecording(false);
        setRecordingDuration(0);
      }
    }
  };

  // 结束按键录音并发送
  const finishPressRecord = () => {
    if (isPressRecording) {
      try {
        // 停止计时
        if (recordTimer.current) {
          clearInterval(recordTimer.current);
          recordTimer.current = null;
        }

        // 如果录音时间太短（小于1秒），视为无效
        if (recordingDuration < 1) {
          cancelPressRecord();
          return;
        }

        // 停止录音并发送 (transcription client will emit completed event)
        transcriptionClientRef.current?.stop();
        setIsPressRecording(false);

        // 显示提示
        message.success(`发送了 ${recordingDuration} 秒的语音消息`);
      } catch (error: any) {
        message.error(`结束录音错误: ${error.message || '未知错误'}`);
        console.error('结束录音错误:', error);
      }
    }
  };

  // 取消按键录音
  const cancelPressRecord = async () => {
    if (isPressRecording) {
      try {
        // 停止计时
        if (recordTimer.current) {
          clearInterval(recordTimer.current);
          recordTimer.current = null;
        }

        // 取消录音
        await transcriptionClientRef.current?.stop();
        setIsPressRecording(false);
        setIsCancelRecording(false);

        // 显示提示
        message.info('取消了语音消息');
      } catch (error: any) {
        message.error(`取消录音错误: ${error.message || '未知错误'}`);
        console.error('取消录音错误:', error);
      }
    }
  };



  return (
    <Layout className="chat-page">
      <Settings
        onSettingsChange={handleSettingsChange}
        localStorageKey={localStorageKey}
        fields={['base_ws_url', 'bot_id', 'pat', 'voice_id', 'workflow_id']}
        className="settings-button"
      />
      <Layout.Content style={{ padding: '16px', background: '#fff' }}>
        <Row justify="center">
          <Col
            span={24}
            style={{
              display: 'flex',
              justifyContent: 'center',
              gap: '8px', // 添加按钮之间的间距
            }}
          >
            <Button size="large" onClick={() => setIsConfigModalOpen(true)}>
              配置
            </Button>
            {!isConnected && (
              <Button
                type="primary"
                size="large"
                icon={<AudioOutlined />}
                danger={isConnected}
                loading={isConnecting}
                disabled={isConnected || isConnecting}
                onClick={() => {
                  setIsConnecting(true);
                  handleConnect().finally(() => {
                    setIsConnecting(false);
                  });
                }}
              >
                开始对话
              </Button>
            )}
            {isConnected && (
              <Operation
                isConnected={isConnected}
                transcriptionClientRef={transcriptionClientRef}
                speechClientRef={speechClientRef}
                setIsConnected={setIsConnected}
                audioMutedDefault={
                  audioConfigRef.current?.getSettings()?.audioMutedDefault ??
                  false
                }
              />
            )}
          </Col>
        </Row>
        <Row style={{ marginTop: '10px' }}>
          <Col
            span={24}
            style={{
              display: 'flex',
              justifyContent: 'center',
              gap: '8px', // 添加按钮之间的间距
            }}
          >
            <Select
              style={{ width: '200px' }}
              size="large"
              placeholder="选择输入设备"
              value={selectedInputDevice}
              onChange={handleSetAudioInputDevice}
            >
              {inputDevices.map(device => (
                <Select.Option key={device.deviceId} value={device.deviceId}>
                  {device.label || `麦克风 ${device.deviceId.slice(0, 8)}...`}
                </Select.Option>
              ))}
            </Select>
            <span style={{ display: 'flex', alignItems: 'center' }}>
              <Tooltip title="设置播放音量">
                {volume === 0 ? (
                  <SoundOutlined style={{ fontSize: '20px', color: '#999' }} />
                ) : (
                  <SoundFilled style={{ fontSize: '20px', color: '#1890ff' }} />
                )}
              </Tooltip>
              <Slider
                value={volume}
                min={0}
                max={100}
                onChange={handleVolumeChange}
                style={{ width: '120px' }}
                tooltip={{ formatter: value => `${value}%` }}
              />
            </span>
          </Col>
        </Row>
        <SendMessage isConnected={isConnected} onSendText={handleSendText} />

        <Row style={{ margin: '16px 0' }}>
          <Col span={24} style={{ textAlign: 'left' }}>
            语音识别结果：{transcript}
          </Col>
        </Row>

        {/* 按键说话功能区 */}
        {turnDetectionType === 'client_interrupt' && isConnected && (
          <Row style={{ maxWidth: '400px', margin: 'auto' }}>
            <Col span={24}>
              <div
                className={`voice-button ${isPressRecording ? 'recording' : ''}`}
                onMouseDown={handleVoiceButtonMouseDown}
                onMouseUp={handleVoiceButtonMouseUp}
                onMouseLeave={handleVoiceButtonMouseLeave}
                onMouseMove={handleVoiceButtonMouseMove}
                onTouchStart={handleVoiceButtonMouseDown}
                onTouchEnd={handleVoiceButtonMouseUp}
                onTouchCancel={handleVoiceButtonMouseLeave}
                onTouchMove={handleVoiceButtonMouseMove}
              >
                {isPressRecording ? '松开 发送' : '按住 说话'}
              </div>

              {/* 录音状态提示 */}
              {isPressRecording && (
                <div className="recording-status">
                  <div className="recording-time">
                    {Math.floor(recordingDuration / 60)
                      .toString()
                      .padStart(2, '0')}
                    :{(recordingDuration % 60).toString().padStart(2, '0')}
                  </div>
                  <div className="recording-progress-container">
                    <div
                      className="recording-progress"
                      style={{
                        width: `${(recordingDuration / maxRecordingTime) * 100}%`,
                      }}
                    ></div>
                  </div>
                  <div
                    className={`recording-tip ${isCancelRecording ? 'cancel-tip' : ''}`}
                  >
                    {isCancelRecording ? '松开手指，取消发送' : '上滑取消发送'}
                  </div>
                </div>
              )}
            </Col>
          </Row>
        )}

        {/* 状态指示器 */}
        {turnDetectionType === 'client_interrupt' && (
          <Row style={{ margin: '16px 0' }}>
            <Col span={24}>
              <div className="status-indicator">
                <div
                  className={`status-dot ${isConnected ? (isMuted ? 'muted' : 'active') : 'inactive'}`}
                ></div>
                <Text>
                  {isConnected
                    ? isMuted
                      ? '麦克风已关闭'
                      : '麦克风已打开'
                    : '未连接'}
                </Text>
              </div>
            </Col>
          </Row>
        )}

        {/* 根据回复模式选择对应的消息组件 */}
        {replyMode === 'stream' ? (
          <ReceiveMessage messageList={messageList} audioList={audioList} />
        ) : (
          <SentenceMessage ref={sentenceMessageRef} clientRef={{ current: undefined } as any} />
        )}
        {isMobile && <ConsoleLog />}

        {/* 配置弹窗 */}
        <Modal
          title="高级配置"
          open={isConfigModalOpen}
          onCancel={() => setIsConfigModalOpen(false)}
          footer={null}
          destroyOnClose={false}
          forceRender
        >
          <AudioConfig clientRef={{ current: undefined } as any} ref={audioConfigRef} />
          <EventInput
            defaultValue={
              localStorage.getItem('chatUpdate') ||
              JSON.stringify(getChatUpdateConfig(turnDetectionType), null, 2)
            }
          />
        </Modal>
        <Card title="使用说明" style={{ marginTop: '20px' }}>
          <Paragraph>
            <ol>
              <li>确保授予浏览器麦克风访问权限</li>
              <li>在右上角 Settings 中配置个人访问令牌 (PAT) 和智能体 ID</li>
              <li>点击"开始对话"按钮建立与智能体的语音连接</li>
              <li>开始对话 - 您的语音将实时转录，智能体会自动回复</li>
              <li>您可以随时使用"打断对话"按钮中断智能体的回复</li>
              <li>使用"静音"按钮可以暂时关闭麦克风输入</li>
              <li>您也可以通过文本框发送文本消息与智能体交流</li>
              <li>完成后点击"断开连接"按钮结束会话</li>
            </ol>
          </Paragraph>
        </Card>
      </Layout.Content>
    </Layout>
  );
}

export default Chat;

