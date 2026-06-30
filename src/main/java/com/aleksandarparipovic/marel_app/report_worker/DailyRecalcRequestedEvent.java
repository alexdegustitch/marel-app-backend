package com.aleksandarparipovic.marel_app.report_worker;

/** Marker event to trigger async queue processing after commit. */
public record DailyRecalcRequestedEvent(Type type) {

	public enum Type {
		DAILY,
		MONTHLY
	}
}


