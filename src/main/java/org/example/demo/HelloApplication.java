package org.example.demo;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.example.demo.chat.ChatController;

import java.io.IOException;

public class HelloApplication extends Application {

  @Override
  public void start(Stage stage) throws IOException {
    FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
    Scene scene = new Scene(fxmlLoader.load(), 550, 700);

    ChatController controller = fxmlLoader.getController();
    stage.setOnCloseRequest(e -> controller.shutdown());

    stage.setTitle("Twitch Chat Client");
    stage.setScene(scene);
    stage.show();
  }
}