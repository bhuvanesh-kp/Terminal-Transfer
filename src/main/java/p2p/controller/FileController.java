package p2p.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import p2p.service.FileSharer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.IOUtils;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "terminal-upload";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if  (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/u", new CORSHandler());

        server.setExecutor(executorService);
    }

    public void start(){
        server.start();
        System.out.println("Server started on port " + server.getAddress().getPort());
    }

    public void stop(){
        server.stop(0);
        executorService.shutdown();
        System.out.println("API service stopped");
    }

    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET , POST , OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    private static class MultipartParser{
        private final byte[] data;
        private final String boundary;

        public MultipartParser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse(){
            try{
                String dataAsString = new String(data);
                String fileNameMarker = "filename=\"";

                int fileNameStart = dataAsString.indexOf(fileNameMarker);
                if (fileNameStart == -1){
                    return null;
                }

                fileNameStart += fileNameMarker.length();
                int fileNameEnd = dataAsString.indexOf("\"", fileNameStart);
                String fileName = dataAsString.substring(fileNameStart, fileNameEnd);

                String contentTypeMarker = "content-type=\"";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker, fileNameEnd);
                String contentType = "application/octet-stream";

                if (contentTypeStart != -1){
                    contentTypeStart +=  contentTypeMarker.length();
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);
                }

                String headerEndMarker = "\r\n\r\n";
                int headerEnd = dataAsString.indexOf(headerEndMarker);
                if (headerEnd == -1){
                    return null;
                }

                int contentStart = headerEnd +  headerEndMarker.length();

                byte[] boundaryBytes = ("\r\n--" + boundary + "--") .getBytes();
                int contentEnd = findSequence(data, boundaryBytes, contentStart);

                if (contentEnd == -1){
                    boundaryBytes = ("\r\n--" + boundary).getBytes();
                    contentEnd = findSequence(data, boundaryBytes, contentStart);
                }

                if (contentEnd == -1 || contentEnd <= contentStart){
                    return null;
                }

                byte[] fileContent = new  byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);

                return new ParseResult(fileName, contentType, fileContent);
            }
            catch (Exception ex){
                System.err.println("Error parsing multipart data: " + ex.getMessage());
                return null;
            }
        }

        private int findSequence(byte[] data, byte[] boundaryBytes, int contentStart) {
            outer:
            for (int i = contentStart; i <= data.length - boundaryBytes.length; i++) {
                for (int j = 0; j< boundaryBytes.length; j++) {
                    if (data[i+j] != boundaryBytes[j]){
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        private static class ParseResult {
            private final String fileName;
            private final String contentType;
            private final byte[] fileContent;

            public ParseResult(String fileName, String contentType, byte[] fileContent) {
                this.fileName = fileName;
                this.contentType = contentType;
                this.fileContent = fileContent;
            }
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");

            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                String response = "Bad Request: Content-Type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            try{
                String boundary = contentType.substring(contentType.indexOf("boundary="), 9);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                IOUtils.copy(exchange.getRequestBody(), baos);
                byte[] responseData = baos.toByteArray();

                MultipartParser parse = new  MultipartParser(responseData, boundary);
                MultipartParser.ParseResult  result = parse.parse();

                if (result == null){
                    String response = "Bad Request: Could not parse the file content";
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try(OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    return;
                }

                String fileName = result.fileName;
                if (fileName == null || fileName.trim().isEmpty()){
                    fileName = "unnamed-file";
                }

                String uniqueFileName = UUID.randomUUID().toString() + "_" + new File(fileName).getName();
                String filePath = uploadDir + File.separator + uniqueFileName;

                try(FileOutputStream fos = new FileOutputStream(filePath)){
                    fos.write(result.fileContent);
                }

                int port = fileSharer.offerFile(filePath);

                new Thread(() -> fileSharer.startFileServer(port)).start();

                String jsonResponse = "{\\\"port\\\": \" + port + \"}";
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(jsonResponse.getBytes());
                }
            }
            catch (Exception ex){
                System.err.println("Error uploading file: " + ex.getMessage());
                String response = "Server error: " +  ex.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try(OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    }

    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {

        }
    }
}
