package p2p.service;

import p2p.utils.UploadUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

public class FileSharer {
    HashMap<Integer, String> availableFiles;

    public FileSharer() {
        this.availableFiles = new  HashMap<>();
    }

    public int offerFile(String filePath){
        int port;
        while(true){
            port = UploadUtils.generateCode();
            if (!availableFiles.containsKey(port)){
                availableFiles.put(port, filePath);
                return port;
            }
        }
    }

    public void startFileServer(int port){
        String filePath = availableFiles.get(port);
        if (filePath == null){
            System.err.println("No file is associated with the port " + port);
            return;
        }

        try(ServerSocket socket = new ServerSocket(port)){
            System.out.println("Serving file '" + new File(filePath).getName() + "' on port " + port);
            Socket clientSocket = socket.accept();
            System.out.println("Client connected from " +  clientSocket.getInetAddress());

            new Thread(new FileSenderHandler(clientSocket, filePath)).start();
        }
        catch (IOException ex){
            System.err.println("Error opening file port " + port);
        }
    }

    public static class FileSenderHandler implements Runnable {
        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath) {
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try(FileInputStream fileInputStream = new FileInputStream(filePath);
                OutputStream oss = clientSocket.getOutputStream();){

                // sender the filename as header
                String fileName = new File(filePath).getName();
                String header = "FileName:" +  fileName + "\n";
                oss.write(header.getBytes());

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    oss.write(buffer, 0, bytesRead);
                }

                System.out.println(fileName + " has been sent to the client at " + clientSocket.getInetAddress());
            }
            catch (IOException ex){
                System.err.println("Error opening file port " + filePath);
            }
            finally{
                try{
                    clientSocket.close();
                }
                catch (IOException ex){
                    System.err.println("Error closing file port " + ex.getMessage());
                }
            }
        }
    }
}
