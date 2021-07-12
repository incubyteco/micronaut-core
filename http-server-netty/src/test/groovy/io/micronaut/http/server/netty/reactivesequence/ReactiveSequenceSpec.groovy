package io.micronaut.http.server.netty.reactivesequence

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import io.reactivex.Flowable
import org.reactivestreams.Publisher
import spock.lang.Shared
import spock.lang.Specification

class ReactiveSequenceSpec extends Specification {

    @Shared
    Map<String, Object> inventoryConfig = [
            'micronaut.application.name': 'inventory',
            "spec.name":  "ReactiveSequenceSpec.inventory",
    ]

    @Shared
    Map<String, Object> booksConfig = [
            'micronaut.application.name': 'books',
            "spec.name":  "ReactiveSequenceSpec.books",
    ]

    @Shared
    Map<String, Object> gatewayConfig = [
            "spec.name":  "ReactiveSequenceSpec.gateway",
            'micronaut.application.name': 'gateway',
    ]

    void "test reactive sequence with timeout"() {
        given:
        EmbeddedServer inventoryEmbeddedServer = ApplicationContext.run(EmbeddedServer, inventoryConfig)
        EmbeddedServer booksEmbeddedServer = ApplicationContext.run(EmbeddedServer, booksConfig)
        Map<String, Object> conf = gatewayConfig + [
                'micronaut.http.client.read-idle-timeout': '5s',
                'micronaut.http.client.read-timeout': '5s',
                'micronaut.http.services.books.url': booksEmbeddedServer.getURL().toString(),
                'micronaut.http.services.inventory.url': inventoryEmbeddedServer.getURL().toString(),
        ]
        EmbeddedServer gatewayEmbeddedServer = ApplicationContext.run(EmbeddedServer, conf)

        when:
        InventoryController inventoryController = inventoryEmbeddedServer.applicationContext.getBean(InventoryController)
        inventoryController.timeout = true
        HttpClient gatewayClient = gatewayEmbeddedServer.applicationContext.createBean(HttpClient, gatewayEmbeddedServer.getURL())
        gatewayClient.toBlocking().retrieve(HttpRequest.GET("/api/gateway"), Argument.listOf(Book))

        then:
        noExceptionThrown()

        cleanup:
        inventoryEmbeddedServer.close()
        booksEmbeddedServer.close()
        gatewayEmbeddedServer.close()
    }

    void "test reactive sequence"() {
        given:
        EmbeddedServer inventoryEmbeddedServer = ApplicationContext.run(EmbeddedServer, inventoryConfig)
        EmbeddedServer booksEmbeddedServer = ApplicationContext.run(EmbeddedServer, booksConfig)
        Map<String, Object> conf = gatewayConfig + [
                'micronaut.http.services.books.url': booksEmbeddedServer.getURL().toString(),
                'micronaut.http.services.inventory.url': inventoryEmbeddedServer.getURL().toString(),
        ]
        EmbeddedServer gatewayEmbeddedServer = ApplicationContext.run(EmbeddedServer, conf)
        HttpClient gatewayClient = gatewayEmbeddedServer.applicationContext.createBean(HttpClient, gatewayEmbeddedServer.getURL())

        when:
        List<Book> books = gatewayClient.toBlocking().retrieve(HttpRequest.GET("/api/gateway"), Argument.listOf(Book))

        then:
        noExceptionThrown()
        books
        books.size() == 2

        cleanup:
        inventoryEmbeddedServer.close()
        booksEmbeddedServer.close()
        gatewayEmbeddedServer.close()
    }

    @Requires(property = "spec.name", value = "ReactiveSequenceSpec.inventory")
    @Controller("/api")
    static class InventoryController {

        boolean timeout

        @Produces(MediaType.TEXT_PLAIN)
        @Get("/inventory/{isbn}")
        HttpResponse<Integer> inventory(String isbn) {
            if (isbn.equals("1491950358")) {
                return HttpResponse.ok(2)
            } else if (isbn.equals("1680502395")) {
                if (timeout) {
                    sleep(8_0000) // sleep for 8 seconds to ensure HTTP Clients timeout when calling
                }
                return HttpResponse.ok(3)
            } else {
                return HttpResponse.notFound()
            }
        }
    }

    @Requires(property = "spec.name", value = "ReactiveSequenceSpec.books")
    @Controller("/api")
    static class BooksController {
        @Get("/books")
        List<Book> list() {
            [
                    new Book("1491950358", "Building Microservices"),
                    new Book("1680502395", "Release It!")
            ]
        }
    }

    @Requires(property = "spec.name", value = "ReactiveSequenceSpec.gateway")
    @Controller("/api")
    static class GatewayController {

        private final BooksClient booksClient;
        private final InventoryClient inventoryClient;

        GatewayController(BooksClient booksClient,
                          InventoryClient inventoryClient) {
            this.booksClient = booksClient;
            this.inventoryClient = inventoryClient;
        }

        @Get("/gateway")
        Publisher<Book> findAll() {
            Flowable.fromPublisher(booksClient.fetchBooks())
                    .flatMapMaybe(b -> {
                        Flowable.fromPublisher(inventoryClient.inventory(b.getIsbn())).singleElement()
                                .filter({ stock -> stock > 0 })
                                .map({ stock ->
                                    b.setStock(stock);
                                    return b
                                })
                    })
        }
    }

    @Requires(property = "spec.name", value = "ReactiveSequenceSpec.gateway")
    @Client("books")
    static interface BooksClient {
        @Get("/api/books")
        Publisher<Book> fetchBooks()
    }

    @Requires(property = "spec.name", value = "ReactiveSequenceSpec.gateway")
    @Client("inventory")
    static interface InventoryClient {
        @Consumes(MediaType.TEXT_PLAIN)
        @Get("/api/inventory/{isbn}")
        @SingleResult
        Publisher<Integer> inventory(String isbn)
    }

    static class Book {
        String isbn
        String name
        Integer stock

        Book() {}

        Book(String isbn, String name) {
            this.isbn = isbn
            this.name = name
        }
    }
}
