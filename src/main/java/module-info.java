module org.example.demo {
  requires javafx.controls;
  requires javafx.fxml;
  requires javafx.web;
  requires jdk.jsobject;
  requires java.net.http;
  requires com.google.gson;
  requires java.desktop;


  opens org.example.demo to javafx.fxml;
  opens org.example.demo.settings to javafx.fxml;
  opens org.example.demo.config to com.google.gson;
  exports org.example.demo;
  exports org.example.demo.chat;
  opens org.example.demo.chat to javafx.fxml;
}