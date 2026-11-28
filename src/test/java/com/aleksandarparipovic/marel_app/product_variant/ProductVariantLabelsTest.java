package com.aleksandarparipovic.marel_app.product_variant;

import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttribute;
import com.aleksandarparipovic.marel_app.variant_attribute.VariantAttributeOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a variant is spelled on a screen, in a mail and on the printed sheet.
 *
 * <p>Two labels on purpose, and they are not interchangeable: displayLabel()
 * names the variant ("Al / Prohrom", or whatever somebody called it), while
 * attributesLabel() says WHICH ATTRIBUTE holds which value. Only the second one
 * can follow a product name and still be read by someone who does not already
 * know the catalogue.
 */
class ProductVariantLabelsTest {

    private static VariantAttribute attribute(String name, int sortOrder) {
        VariantAttribute attribute = new VariantAttribute();
        attribute.setName(name);
        attribute.setSortOrder(sortOrder);
        return attribute;
    }

    private static ProductVariantValue value(VariantAttribute attribute, String optionValue) {
        VariantAttributeOption option = new VariantAttributeOption();
        option.setValue(optionValue);
        option.setAttribute(attribute);
        return ProductVariantValue.builder().attribute(attribute).option(option).build();
    }

    private static ProductVariant variant(String label, ProductVariantValue... values) {
        return ProductVariant.builder()
                .label(label)
                .signature("x")
                .values(new java.util.ArrayList<>(List.of(values)))
                .build();
    }

    @Test
    void namesTheAttributeBesideEachValue() {
        ProductVariant v = variant(null,
                value(attribute("Šraf", 1), "Al"),
                value(attribute("Navrtka", 2), "Prohrom"));

        assertThat(v.attributesLabel()).isEqualTo("Šraf: Al · Navrtka: Prohrom");
    }

    /** The attributes' own order, so two variants of a product read alike. */
    @Test
    void followsTheAttributeOrderOfTheType() {
        ProductVariant v = variant(null,
                value(attribute("Navrtka", 2), "Prohrom"),
                value(attribute("Šraf", 1), "Al"));

        assertThat(v.attributesLabel()).isEqualTo("Šraf: Al · Navrtka: Prohrom");
    }

    /**
     * A hand-given name is what the variant is CALLED; it is not an answer to
     * "which attribute is in which value", so it never replaces the attributes.
     */
    @Test
    void ignoresTheHandGivenNameWhenValuesExist() {
        ProductVariant v = variant("Varijanta 2", value(attribute("Šraf", 1), "Al"));

        assertThat(v.displayLabel()).isEqualTo("Varijanta 2");
        assertThat(v.attributesLabel()).isEqualTo("Šraf: Al");
    }

    /** Nothing to spell out: the caller leaves the tail off rather than printing "". */
    @Test
    void saysNothingWhenTheVariantCarriesNoValues() {
        assertThat(variant("Varijanta 2").attributesLabel()).isNull();
    }
}
