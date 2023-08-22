package org.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.commons.lang3.concurrent.TimedSemaphore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CrptApi {
    static HttpClient httpClient = HttpClient.newBuilder().build();
    static ExecutorService executorService;
    static TimedSemaphore semaphore;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        executorService = Executors.newFixedThreadPool(ConnectionProperties.NUMBER_OF_THREADS);
        semaphore = new TimedSemaphore(ConnectionProperties.PERIOD_LENGTH, timeUnit, requestLimit);
    }

    /**
     * 1 шаг (getKey()): запрос авторизаций (1.2.1), возвращает ключ в виде строки
     * 2 шаг (getToken(Key key)): получение аутентификационного токена (1.2.2),
     * отправляет ключ с полями uuid и data в виде строк, получает токен в виде строки или ошибку
     * 3 шаг: создание документа
     */

    public static Key getKey() {
        HttpRequest keyRequest = HttpRequest
                .newBuilder(URI.create(ApiResources.URL_FOR_AUTHORIZATION))
                .GET()
                .build();
        try {
            HttpResponse<String> httpResponse = httpClient.send(keyRequest, HttpResponse.BodyHandlers.ofString());
            return new ObjectMapper().readValue(httpResponse.body(), Key.class);
        }
        catch (IOException | InterruptedException e) {

        }
        return null;
    }

    public static Token getToken(Key key) {
        try {
            HttpRequest tokenRequest = HttpRequest
                    .newBuilder(URI.create(ApiResources.URL_FOR_TOKEN))
                    .header("content-type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(key)))
                    .build();

            HttpResponse<String> response = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            return new ObjectMapper().readValue(response.body(), Token.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        catch (IOException | InterruptedException e) {

        }
        return null;
    }

    public static void LP_INTRODUCE_GOODS(Document document) {
        Key key = getKey();
        Token authToken = getToken(key);

        try {
            if (authToken != null) {
                HttpRequest postDocument = HttpRequest
                        .newBuilder(URI.create(ApiResources.URL_CREATE_DOCUMENT))
                        .header("Authorization", "Bearer: " + authToken.getToken())//page 7
                        .header("content-type", "application/json;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(document)))
                        .build();

                executorService.submit(() -> {
                            try {
                                semaphore.acquire();
                                httpClient.send(postDocument, HttpResponse.BodyHandlers.ofString());
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
            }


        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
   private static class Key {
        String uuid;
        String data;
   }
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
   private static class Token {
        String token;
    }

    @FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
    public static class ConnectionProperties {
        static int NUMBER_OF_THREADS = 1;
        static long PERIOD_LENGTH = 1;
    }

    @FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
    public static class ApiResources {
        static String URL_FOR_AUTHORIZATION = "https://ismp.crpt.ru/api/v3/auth/cert/key";
        static String URL_FOR_TOKEN = "https://ismp.crpt.ru/api/v3/auth/cert/";
        static String URL_CREATE_DOCUMENT = "https://ismp.crpt.ru/api/v3/lk/documents/create?pg=";
    }
    
    public static class Document {

    }

}
