/*
 * This file is part of TrackWorkTime (TWT).
 * 
 * TWT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * TWT is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with TWT. If not, see <http://www.gnu.org/licenses/>.
 */
package org.zephyrsoft.trackworktime.model;

import org.zephyrsoft.trackworktime.util.DateTimeUtil;

/**
 * Holds a total amount of time without any reference to when this time was. Doesn't have to be positive.
 * 
 * @author Mathis Dirksen-Thedens
 */
public class TimeSum {

	/** can also be counted negative */
	private int hours = 0;
	/** always counted positive */
	private int minutes = 0;

	/**
	 * Add some hours and minutes. The minutes value doesn't have to be < 60, but both values have to be >= 0.
	 */
	public void add(int hoursToAdd, int minutesToAdd) {
		if (hoursToAdd < 0 || minutesToAdd < 0) {
			throw new IllegalArgumentException("both values have to be >= 0");
		}
		hours += hoursToAdd;
		minutes += minutesToAdd;
		balance();
	}

	/**
	 * Subtracts some hours and minutes. The minutes value doesn't have to be < 60, but both values have to be >= 0.
	 */
	public void substract(int hoursToSubstract, int minutesToSubstract) {
		if (hoursToSubstract < 0 || minutesToSubstract < 0) {
			throw new IllegalArgumentException("both values have to be >= 0");
		}
		hours -= hoursToSubstract;
		minutes -= minutesToSubstract;
		balance();
	}

	/**
	 * Add or substract the value of the given time sum.
	 */
	public void addOrSubstract(TimeSum timeSum) {
		if (timeSum == null) {
			return;
		}
		hours += timeSum.hours;
		minutes += timeSum.minutes;
		balance();
	}

	private void balance() {
		while (minutes >= 60) {
			hours += 1;
			minutes -= 60;
		}
		while (minutes < 0) {
			hours -= 1;
			minutes += 60;
		}
	}

	@Override
	public String toString() {
		int hoursForDisplay = hours;
		int minutesForDisplay = minutes;
		boolean negative = false;
		if (hoursForDisplay < 0) {
			negative = true;
			if (minutesForDisplay != 0) {
				hoursForDisplay += 1;
				minutesForDisplay = 60 - minutesForDisplay;
			}
		}
		return (negative && hoursForDisplay == 0 ? "-" : "") + String.valueOf(hoursForDisplay) + ":"
			+ DateTimeUtil.padToTwoDigits(minutesForDisplay);
	}

	/**
	 * Get the time sum as accumulated value in minutes.
	 */
	public int getAsMinutes() {
		return hours * 60 + minutes;
	}

	public void reset() {
		hours = 0;
		minutes = 0;
	}

}
