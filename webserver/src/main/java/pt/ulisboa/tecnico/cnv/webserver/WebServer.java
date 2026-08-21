package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.dna.DnaHandler;
import pt.ulisboa.tecnico.cnv.fractals.FractalsHandler;
import pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler;

public class WebServer {
    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 8000;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new FractalsHandler());
        server.createContext("/dna", new DnaHandler());
        server.createContext("/grayscott", new GrayScottHandler());
        server.createContext("/test", new TestHandler());
        server.start();
    }

    static class TestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = "OK";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}
