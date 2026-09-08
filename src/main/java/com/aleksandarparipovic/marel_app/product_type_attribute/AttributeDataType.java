package com.aleksandarparipovic.marel_app.product_type_attribute;

/**
 * How an attribute's value is interpreted and validated.
 *
 * <p>Stored as the enum name in {@code product_type_attributes.data_type}, whose
 * CHECK constraint accepts exactly these two. TEXT is the safe default: a value
 * that will not parse as a number is still a value ("16-95", "2/1").
 */
public enum AttributeDataType {
    /** A number; the value must parse as one. Used for d1, L, weight… */
    NUMBER,
    /** Free text; anything non-blank. Used for ranges and codes like "16-95". */
    TEXT
}
