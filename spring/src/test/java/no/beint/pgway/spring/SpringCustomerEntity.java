package no.beint.pgway.spring;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "spring_customers")
public class SpringCustomerEntity {
    @Id
    private Integer id;
    private String name;

    protected SpringCustomerEntity() {}

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
