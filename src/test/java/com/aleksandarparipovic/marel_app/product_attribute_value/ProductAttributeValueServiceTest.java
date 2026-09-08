package com.aleksandarparipovic.marel_app.product_attribute_value;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.product_attribute_value.dto.ProductAttributeValueDto;
import com.aleksandarparipovic.marel_app.product_attribute_value.dto.ProductAttributeValuesSaveRequest;
import com.aleksandarparipovic.marel_app.product_family.ProductFamily;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import com.aleksandarparipovic.marel_app.product_type_attribute.AttributeDataType;
import com.aleksandarparipovic.marel_app.product_type_attribute.ProductTypeAttribute;
import com.aleksandarparipovic.marel_app.product_type_attribute.ProductTypeAttributeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one rule the database cannot enforce: a spec value may only be recorded
 * against an attribute that belongs to the product's own type.
 *
 * <p>Nothing in SQL catches a value written against another type's attribute —
 * a product's type is nullable, so no composite foreign key reaches it. This
 * service is the only place it can be stopped, which is why it is tested here.
 */
class ProductAttributeValueServiceTest {

    private ProductAttributeValueService service;

    private ProductTypeAttributeRepository attributeRepository;
    private ProductAttributeValueRepository valueRepository;
    private ProductRepository productRepository;

    private List<ProductAttributeValue> stored;

    // The product under test belongs to typeA. attrA is typeA's; attrB is typeB's.
    private ProductType typeA;
    private Product product;
    private ProductTypeAttribute attrA;
    private ProductTypeAttribute attrB;

    @BeforeEach
    void setUp() {
        attributeRepository = mock(ProductTypeAttributeRepository.class);
        valueRepository = mock(ProductAttributeValueRepository.class);
        productRepository = mock(ProductRepository.class);
        service = new ProductAttributeValueService(valueRepository, attributeRepository, productRepository);

        ProductFamily family = new ProductFamily();
        family.setId(1L);

        typeA = new ProductType();
        typeA.setId(10L);
        typeA.setFamily(family);

        ProductType typeB = new ProductType();
        typeB.setId(20L);
        typeB.setFamily(family);

        product = Product.builder().productName("CuCPST 10.6").build();
        product.setId(100L);
        product.setProductType(typeA);

        attrA = ProductTypeAttribute.builder()
                .productType(typeA).name("L").dataType(AttributeDataType.NUMBER)
                .sortOrder(0).required(false).isActive(true).build();
        attrA.setId(1000L);

        attrB = ProductTypeAttribute.builder()
                .productType(typeB).name("d1").dataType(AttributeDataType.TEXT)
                .sortOrder(0).required(false).isActive(true).build();
        attrB.setId(2000L);

        stored = new ArrayList<>();

        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(attributeRepository.findById(1000L)).thenReturn(Optional.of(attrA));
        when(attributeRepository.findById(2000L)).thenReturn(Optional.of(attrB));
        when(attributeRepository.findByProductType_IdAndIsActiveTrueOrderBySortOrderAscNameAsc(10L))
                .thenReturn(List.of(attrA));
        when(valueRepository.findByProduct_Id(100L)).thenReturn(stored);
        when(valueRepository.saveAll(any())).thenAnswer(call -> {
            List<ProductAttributeValue> batch = call.getArgument(0);
            stored.addAll(batch);
            return batch;
        });
        // deleteAll(Iterable) returns void — left at Mockito's no-op default; none
        // of these cases reach a delete (they save, or throw before applyReplace).
    }

    @Test
    @DisplayName("A value against an attribute of another type is refused")
    void rejectsAttributeFromAnotherType() {
        ProductAttributeValuesSaveRequest request = requestOf(attrB.getId(), "13");

        assertThatThrownBy(() -> service.saveForProduct(100L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ne pripada tipu");

        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("A value against an attribute of the product's own type is saved")
    void acceptsAttributeOfOwnType() {
        ProductAttributeValuesSaveRequest request = requestOf(attrA.getId(), "24.5");

        List<ProductAttributeValueDto> form = service.saveForProduct(100L, request);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getValue()).isEqualTo("24.5");
        assertThat(form).singleElement()
                .satisfies(row -> {
                    assertThat(row.getAttributeId()).isEqualTo(attrA.getId());
                    assertThat(row.getValue()).isEqualTo("24.5");
                });
    }

    @Test
    @DisplayName("A NUMBER attribute refuses a non-numeric value")
    void rejectsNonNumericForNumberAttribute() {
        ProductAttributeValuesSaveRequest request = requestOf(attrA.getId(), "16-95");

        assertThatThrownBy(() -> service.saveForProduct(100L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mora biti broj");
    }

    @Test
    @DisplayName("A product with no type can hold no values")
    void rejectsWhenProductHasNoType() {
        product.setProductType(null);
        ProductAttributeValuesSaveRequest request = requestOf(attrA.getId(), "24.5");

        assertThatThrownBy(() -> service.saveForProduct(100L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("nema tip");
    }

    private static ProductAttributeValuesSaveRequest requestOf(Long attributeId, String value) {
        ProductAttributeValuesSaveRequest.Item item = new ProductAttributeValuesSaveRequest.Item();
        item.setAttributeId(attributeId);
        item.setValue(value);
        ProductAttributeValuesSaveRequest request = new ProductAttributeValuesSaveRequest();
        request.setValues(List.of(item));
        return request;
    }
}
