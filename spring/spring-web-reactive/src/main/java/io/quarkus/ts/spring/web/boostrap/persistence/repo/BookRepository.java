package io.quarkus.ts.spring.web.boostrap.persistence.repo;

import javax.enterprise.context.ApplicationScoped;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.ts.spring.web.boostrap.persistence.model.Book;
import io.smallrye.mutiny.Multi;

@ApplicationScoped
public class BookRepository implements PanacheRepository<Book> {
    public Multi<Book> findByTitle(String title) {
        return find("title", title).stream();
    }
}
