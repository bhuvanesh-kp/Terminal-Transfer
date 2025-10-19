package p2p;

import p2p.controller.FileController;

import java.io.IOException;

public class App {
    public static void main(String[] args) {
        try{
            FileController fileController = new FileController(8000);
            fileController.start();

            System.out.println("Terminal-Transfer started in port : 8080");
            System.out.println("UI available at port : 3000");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down Terminal-Transfer ...");
                fileController.stop();
            }));

            System.out.println("Press Enter to stop the server");
            System.in.read();
        }
        catch (IOException e){
            System.err.println("Error starting the server " + e.getMessage());
            e.printStackTrace();
        }
    }
}
