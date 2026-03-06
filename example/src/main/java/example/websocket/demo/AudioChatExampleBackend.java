package example.websocket.demo;

import example.websocket.demo.websocket.AudioChatWebSocketServer;

public class AudioChatExampleBackend {

  private static final int DEFAULT_PORT = 8080;
  private AudioChatWebSocketServer server;

  public static void main(String[] args) {
    int port = DEFAULT_PORT;
    if (args.length > 0) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("[错误] 无效的端口号，使用默认端口: " + DEFAULT_PORT);
      }
    }

    AudioChatExampleBackend backend = new AudioChatExampleBackend();
    backend.start(port);
  }

  public void start(int port) {
    System.out.println("========================================");
    System.out.println("  Audio Chat WebSocket Backend Server");
    System.out.println("========================================");
    System.out.println();

    server = new AudioChatWebSocketServer(port);

    // Setup shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    // Start server
    server.start();

    System.out.println();
    System.out.println("服务器已启动，监听端口: " + port);
    System.out.println("WebSocket 地址: ws://localhost:" + port);
    System.out.println();
    System.out.println("按 Ctrl+C 停止服务器...");

    // Keep main thread alive
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void shutdown() {
    System.out.println();
    System.out.println("[系统] 正在关闭服务器...");

    if (server != null) {
      server.shutdown();
    }

    System.out.println("[系统] 服务器已关闭");
  }
}
