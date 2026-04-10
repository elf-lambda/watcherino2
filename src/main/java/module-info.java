module org.example.demo {
  requires javafx.controls;
  requires javafx.fxml;
  requires javafx.web;
  requires jdk.jsobject;
  requires java.net.http;
  requires com.google.gson;
  requires java.desktop;


  opens org.example.demo to javafx.fxml;
  exports org.example.demo;
}