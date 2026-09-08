package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.ProductService;
import com.aleksandarparipovic.marel_app.product.dto.ProductWithOperationListRow;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_family.ProductFamily;
import com.aleksandarparipovic.marel_app.product_family.ProductFamilyRepository;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type.ProductTypeRepository;
import com.aleksandarparipovic.marel_app.search.SearchRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue's global search matches what the business searches by — name, the
 * product code (type code + subtype), catalogue number, description and operation
 * name — and NOT the legacy "šifra" (productCode), which is no longer used.
 */
@Transactional
class ProductGlobalSearchFieldsIT extends AbstractIntegrationTest {

    @Autowired private ProductService products;
    @Autowired private ProductRepository productRepository;
    @Autowired private OperationRepository operationRepository;
    @Autowired private ProductTypeRepository typeRepository;
    @Autowired private ProductFamilyRepository familyRepository;
    @Autowired private PayrollScenarioFixture fixture;

    private Long buildProduct() {
        ProductFamily family = familyRepository.saveAndFlush(
                ProductFamily.builder().name("IT-Fam-" + System.nanoTime()).build());
        ProductType type = typeRepository.saveAndFlush(ProductType.builder()
                .family(family)
                .name("IT-Type")
                .code("ZZTYPECODE")
                .build());

        Product p = new Product();
        p.setProductName("AlphaProduct");
        p.setProductCode("SIFRA12345");     // legacy šifra — must NOT be searchable
        p.setCatalogNumber("CATZ999");
        p.setSubtype("ZS77");
        p.setDescription("opiszz opis teksta");
        p.setProductType(type);
        p.setActive(true);
        p = productRepository.saveAndFlush(p);

        Operation op = fixture.operation(p, fixture.scenario().build().workCategory(), 40);
        op.setOpName("OperZzzUnikat");
        operationRepository.saveAndFlush(op);

        return p.getId();
    }

    private List<Long> searchIds(String term) {
        SearchRequest request = new SearchRequest();
        SearchRequest.Pagination pagination = new SearchRequest.Pagination();
        pagination.setPage(0);
        pagination.setSize(50);
        request.setPagination(pagination);
        request.setGlobalSearch(term);
        return products.searchAll(request).getContent().stream()
                .map(ProductWithOperationListRow::getProductInfo)
                .map(info -> info.getProductId())
                .toList();
    }

    @Test
    @DisplayName("global search matches name, type code, subtype, catalogue number, description and operation")
    void matchesTheBusinessFields() {
        Long id = buildProduct();

        assertThat(searchIds("AlphaProduct")).contains(id);   // naziv
        assertThat(searchIds("ZZTYPECODE")).contains(id);     // kod (tip)
        assertThat(searchIds("ZS77")).contains(id);           // kod (podtip)
        assertThat(searchIds("CATZ999")).contains(id);        // kataloški broj
        assertThat(searchIds("opiszz")).contains(id);         // opis
        assertThat(searchIds("OperZzzUnikat")).contains(id);  // operacija
    }

    @Test
    @DisplayName("global search does NOT match the legacy šifra (productCode)")
    void doesNotMatchSifra() {
        Long id = buildProduct();

        assertThat(searchIds("SIFRA12345")).doesNotContain(id);
    }
}
