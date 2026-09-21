package com.btc.nplus1.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductCatalogDTO implements Serializable {

    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private Integer stockQuantity;
    private List<String> highlights;
    private Map<String, String> specifications;
    private boolean sentinel;

    public ProductCatalogDTO() {}

    public ProductCatalogDTO(String id, String name, String description, BigDecimal price,
                             String category, Integer stockQuantity, List<String> highlights,
                             Map<String, String> specifications) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.stockQuantity = stockQuantity;
        this.highlights = highlights;
        this.specifications = specifications;
        this.sentinel = false;
    }

    public static ProductCatalogDTO emptySentinel() {
        ProductCatalogDTO dto = new ProductCatalogDTO();
        dto.setId("__EMPTY_SENTINEL__");
        dto.setSentinel(true);
        dto.setHighlights(Collections.emptyList());
        dto.setSpecifications(Collections.emptyMap());
        return dto;
    }

    @JsonIgnore
    public boolean isEmptySentinel() {
        return sentinel || "__EMPTY_SENTINEL__".equals(id);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }

    public List<String> getHighlights() { return highlights; }
    public void setHighlights(List<String> highlights) { this.highlights = highlights; }

    public Map<String, String> getSpecifications() { return specifications; }
    public void setSpecifications(Map<String, String> specifications) { this.specifications = specifications; }

    public boolean isSentinel() { return sentinel; }
    public void setSentinel(boolean sentinel) { this.sentinel = sentinel; }
}
