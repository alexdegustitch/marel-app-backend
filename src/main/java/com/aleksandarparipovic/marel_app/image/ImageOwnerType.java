package com.aleksandarparipovic.marel_app.image;

/**
 * Which kind of thing an image is attached to. The path segment is how a URL
 * names it ({@code /api/products/…/images}); the resolver maps that segment to
 * this, and this to the right owner column on {@code images}.
 */
public enum ImageOwnerType {

    PRODUCT_FAMILY("product-families"),
    PRODUCT_TYPE("product-types"),
    PRODUCT("products");

    private final String pathSegment;

    ImageOwnerType(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public String pathSegment() {
        return pathSegment;
    }

    public static ImageOwnerType fromPathSegment(String segment) {
        for (ImageOwnerType type : values()) {
            if (type.pathSegment.equals(segment)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Nepoznat tip vlasnika slike: " + segment);
    }
}
