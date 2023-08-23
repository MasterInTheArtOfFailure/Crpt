package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.apache.commons.lang3.concurrent.TimedSemaphore;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * отправляет ключ с полями uuid и data в виде строк, получает токен в виде строки или ошибку.
     * Не понял, как пользователь после первого получения uuid и data делает электронную подпись и добавляет ее в data
     * 3 шаг: создание документа
     */

    public static HttpResponseInterface getKey() {
        HttpRequest keyRequest = HttpRequest
                .newBuilder(URI.create(ApiResources.URL_FOR_AUTHORIZATION))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(keyRequest, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() == 200) return new ObjectMapper().readValue(response.body(), Key.class);
        }
        catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e.getCause());
        }
        return null;
    }

    public static HttpResponseInterface getToken(Key key, String signature) {
        //судя по документации из пунктов 1.2.1 и 1.2.2 пользователь отправляет ЭП присоединенную, не понятно в какой
        //конкретно момент происходит генерация подписи
        //(видимо это означает, что происходит конкатенация data и signature)
        key.setData(Base64.getEncoder().encodeToString((key.getData() + signature).getBytes()));
        try {
            HttpRequest tokenRequest = HttpRequest
                    .newBuilder(URI.create(ApiResources.URL_FOR_TOKEN))
                    .header("content-type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(key)))
                    .build();
            HttpResponse<String> response = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if(response.body().contains("token")) return new ObjectMapper().readValue(response.body(), Token.class);
        } catch (JsonProcessingException jsonErr) {
            //?
        } catch (IOException | InterruptedException err) {
            throw new RuntimeException(err.getMessage(), err.getCause());
        }
        return null;
    }

    public static void CreateDocument(Document document, String signature) {
        Key key = (Key) getKey();
        assert key != null;

        Token authToken = (Token) getToken(key, signature);

        try {
            assert authToken != null;
            HttpRequest postDocument = HttpRequest
                        .newBuilder(URI.create(ApiResources.URL_CREATE_DOCUMENT))
                        .header("Authorization", "Bearer: " + authToken.getToken())//page 7
                        .header("content-type", "application/json;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(document)))
                        .build();

                executorService.submit(() -> {
                            try {
                                //check if semaphore can grant a permit
                                semaphore.acquire();
                                httpClient.send(postDocument, HttpResponse.BodyHandlers.ofString());
                            } catch (InterruptedException | IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }
    public interface HttpResponseInterface {}

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
    @Setter
    @AllArgsConstructor
    private static class Key implements Serializable, HttpResponseInterface {
        static final long serialVersionUID = 1L;//не обязательное поле, но при изменении класса или использовании
        // другой jvm может возникнуть другой идентификатор, что приведет к невозможности сериализации объектов,
        // которые были созданы в предыдущем варианте класса, можно задать вручную или
        // использовать serialver для генерации SerialVersionUID
        String uuid;
        String data;
    }
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @Getter
    @Setter
    @AllArgsConstructor
    private static class Token implements Serializable, HttpResponseInterface {
        static final long serialVersionUID = 1L;
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

    @FieldDefaults(level = AccessLevel.PRIVATE)
    @AllArgsConstructor
    @Getter
    @Setter
    public static class Document {
        private static final long serialVersionUID = 1L;

        @JsonProperty("document_format")
        String documentFormat;//should be "MANUAL"

        @JsonProperty("product_document")
        String productDocument;//base64 encoded document

        @JsonProperty("product_group")
        String productGroup;//
        @JsonProperty
        String type;//LP_INTRODUCE_GOODS

        String signature;

        public Document(String productDocument, String productGroup, String signature) {
            this.documentFormat = "MANUAL";
            this.productDocument = productDocument;
            this.productGroup = productGroup;
            this.type = "LP_INTRODUCE_GOODS";
            this.signature = signature;
        }
    }

}
