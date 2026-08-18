package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The application boots.
 *
 * <p>Extends the integration base rather than standing alone as a bare
 * {@code @SpringBootTest}. On its own it resolved configuration from
 * {@code application.properties} and connected to whatever PostgreSQL happened
 * to be listening locally, using the credentials that used to be committed
 * there — so it passed on the machine that wrote it and nowhere else. Now it
 * boots against the suite's container and the test profile, which is what the
 * other tests already do.
 *
 * <p>With {@code ddl-auto=validate} this is a real check despite the empty
 * body: the context only starts if every entity mapping matches the schema the
 * migrations just built. A column renamed in an entity but not in SQL fails
 * here first.
 */
class MarelAppApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
