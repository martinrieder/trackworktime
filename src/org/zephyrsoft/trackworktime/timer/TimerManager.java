/*
 * This file is part of TrackWorkTime (TWT).
 *
 * TWT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
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
package org.zephyrsoft.trackworktime.timer;

import hirondelle.date4j.DateTime;
import hirondelle.date4j.DateTime.DayOverflow;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import android.content.SharedPreferences;
import org.zephyrsoft.trackworktime.Basics;
import org.zephyrsoft.trackworktime.database.DAO;
import org.zephyrsoft.trackworktime.model.Event;
import org.zephyrsoft.trackworktime.model.PeriodEnum;
import org.zephyrsoft.trackworktime.model.Task;
import org.zephyrsoft.trackworktime.model.TimeSum;
import org.zephyrsoft.trackworktime.model.TypeEnum;
import org.zephyrsoft.trackworktime.model.Week;
import org.zephyrsoft.trackworktime.model.WeekDayEnum;
import org.zephyrsoft.trackworktime.model.WeekPlaceholder;
import org.zephyrsoft.trackworktime.options.Key;
import org.zephyrsoft.trackworktime.util.DateTimeUtil;
import org.zephyrsoft.trackworktime.util.Logger;

/**
 * Manages the time tracking.
 * 
 * @author Mathis Dirksen-Thedens
 */
public class TimerManager {
	
	private final DAO dao;
	private final SharedPreferences preferences;
	
	/**
	 * Constructor
	 */
	public TimerManager(DAO dao, SharedPreferences preferences) {
		this.dao = dao;
		this.preferences = preferences;
	}
	
	/**
	 * Checks the current state.
	 * 
	 * @return {@code true} if currently clocked in, {@code false} otherwise
	 */
	public boolean isTracking() {
		Event latestEvent = dao.getLastEventBefore(DateTimeUtil.getCurrentDateTime());
		return latestEvent == null ? false : latestEvent.getType().equals(TypeEnum.CLOCK_IN.getValue());
	}
	
	/**
	 * Returns {@code true} if the options are set in a way that an event is in the defined time before/after an
	 * existing event (not counting CLOCK_OUT_NOW).
	 */
	public boolean isInIgnorePeriodForLocationBasedTracking() {
		DateTime now = DateTimeUtil.getCurrentDateTime();
		
		// get first event AFTER now, subtract the minutes to ignore before events and check if the result is BEFORE now
		Event firstAfterNow = dao.getFirstEventAfter(now);
		int ignoreBefore = preferences.getInt(Key.LOCATION_BASED_TRACKING_IGNORE_BEFORE_EVENTS.getName(), 0);
		if (firstAfterNow != null) {
			DateTime firstAfterNowTime = DateTimeUtil.stringToDateTime(firstAfterNow.getTime());
			if (firstAfterNowTime.minus(0, 0, 0, 0, ignoreBefore, 0, DayOverflow.Spillover).lt(now)) {
				return true;
			}
		}
		// get the last event BEFORE now, add the minutes to ignore after events and check if the result is AFTER now
		Event lastBeforeNow = dao.getLastEventBefore(now);
		int ignoreAfter = preferences.getInt(Key.LOCATION_BASED_TRACKING_IGNORE_AFTER_EVENTS.getName(), 0);
		if (lastBeforeNow != null) {
			DateTime lastBeforeNowTime = DateTimeUtil.stringToDateTime(lastBeforeNow.getTime());
			if (lastBeforeNowTime.plus(0, 0, 0, 0, ignoreAfter, 0, DayOverflow.Spillover).gt(now)) {
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Returns the currently active task or {@code null} if tracking is disabled at the moment.
	 */
	public Task getCurrentTask() {
		Event latestEvent = dao.getLastEventBefore(DateTimeUtil.getCurrentDateTime());
		if (latestEvent != null && latestEvent.getType().equals(TypeEnum.CLOCK_IN.getValue())) {
			return dao.getTask(latestEvent.getTask());
		} else {
			return null;
		}
	}
	
	/**
	 * Either starts tracking (from non-tracked time) or changes the task and/or text (from already tracked time).
	 * 
	 * @param minutesToPredate how many minutes in the future should the event be
	 * @param selectedTask the task for which the time shall be tracked
	 * @param text free text to describe in detail what was done
	 */
	public void startTracking(int minutesToPredate, Task selectedTask, String text) {
		createEvent(minutesToPredate, (selectedTask == null ? null : selectedTask.getId()), TypeEnum.CLOCK_IN, text);
		Basics.getInstance().safeCheckPersistentNotification();
	}
	
	/**
	 * Stops tracking time.
	 * 
	 * @param minutesToPredate how many minutes in the future should the event be
	 */
	public void stopTracking(int minutesToPredate) {
		createEvent(minutesToPredate, null, TypeEnum.CLOCK_OUT, null);
		Basics.getInstance().safeCheckPersistentNotification();
	}
	
	/**
	 * Is the event a clock-in event? {@code null} as argument will return {@code false}.
	 */
	public static boolean isClockInEvent(Event event) {
		return event != null && event.getType().equals(TypeEnum.CLOCK_IN.getValue());
	}
	
	/**
	 * Is the event a clock-out event? {@code null} as argument will return {@code false}.
	 */
	public static boolean isClockOutEvent(Event event) {
		return event != null
			&& (event.getType().equals(TypeEnum.CLOCK_OUT.getValue()) || event.getType().equals(
				TypeEnum.CLOCK_OUT_NOW.getValue()));
	}
	
	/**
	 * Calculate a time sum for a given period.
	 */
	public TimeSum calculateTimeSum(DateTime date, PeriodEnum periodEnum) {
		Logger.debug("calculating time sum for {0} containing {1}", periodEnum.name(),
			DateTimeUtil.dateTimeToString(date));
		TimeSum ret = new TimeSum();
		
		DateTime beginOfPeriod = null;
		DateTime endOfPeriod = null;
		List<Event> events = null;
		if (periodEnum == PeriodEnum.DAY) {
			beginOfPeriod = date.getStartOfDay();
			endOfPeriod = beginOfPeriod.plusDays(1);
			events = dao.getEventsOnDay(date);
		} else if (periodEnum == PeriodEnum.WEEK) {
			beginOfPeriod = DateTimeUtil.getWeekStart(date);
			endOfPeriod = beginOfPeriod.plusDays(7);
			Week week = dao.getWeek(DateTimeUtil.dateTimeToString(beginOfPeriod));
			events = dao.getEventsInWeek(week);
		} else {
			throw new IllegalArgumentException("unknown period type");
		}
		Event lastEventBefore = dao.getLastEventBefore(beginOfPeriod);
		
		DateTime clockedInSince = null;
		if (isClockInEvent(lastEventBefore)) {
			clockedInSince = beginOfPeriod;
		}
		
		Event lastEvent = (events.isEmpty() ? null : events.get(events.size() - 1));
		DateTime lastEventTime = (lastEvent == null ? null : DateTimeUtil.stringToDateTime(lastEvent.getTime()));
		
		// insert CLOCK_OUT_NOW event if applicable
		if (isClockInEvent(lastEvent) && DateTimeUtil.isInPast(lastEventTime) && DateTimeUtil.isInFuture(endOfPeriod)) {
			Event clockOutNowEvent = createClockOutNowEvent();
			events.add(clockOutNowEvent);
			lastEvent = clockOutNowEvent;
		}
		
		if (DateTimeUtil.isInFuture(beginOfPeriod)) {
			// if the period is completely in the future, it has no work time
			return ret;
		}
		
		for (Event event : events) {
			DateTime eventTime = DateTimeUtil.stringToDateTime(event.getTime());
			
			// clock-in event while not clocked in? => remember time
			if (clockedInSince == null && isClockInEvent(event)) {
				clockedInSince = eventTime;
			}
			// clock-out event while clocked in? => add time since last clock-in to result
			if (clockedInSince != null && isClockOutEvent(event)) {
				ret.substract(clockedInSince.getHour(), clockedInSince.getMinute());
				ret.add(eventTime.getHour(), eventTime.getMinute());
				clockedInSince = null;
			}
		}
		
		if (lastEvent != null && lastEvent.getType().equals(TypeEnum.CLOCK_OUT_NOW.getValue())) {
			// try to substract the auto-pause for today because it might be not counted in the database yet
			DateTime eventTime = DateTimeUtil.stringToDateTime(lastEvent.getTime());
			if (isAutoPauseEnabled() && isAutoPauseApplicable(eventTime)) {
				DateTime autoPauseBegin = getAutoPauseBegin(eventTime);
				DateTime autoPauseEnd = getAutoPauseEnd(eventTime);
				ret.substract(autoPauseEnd.getHour(), autoPauseEnd.getMinute());
				ret.add(autoPauseBegin.getHour(), autoPauseBegin.getMinute());
			}
		}
		
		if (clockedInSince != null) {
			// still clocked in at end of period: count hours and minutes
			ret.substract(clockedInSince.getHour(), clockedInSince.getMinute());
			ret.add(24, 0);
			if (periodEnum == PeriodEnum.WEEK) {
				// calculating for week: also count days
				DateTime counting = clockedInSince.minusDays(1);
				while (counting.lt(endOfPeriod)) {
					ret.add(24, 0);
					counting = counting.plusDays(1);
				}
			}
		}
		
		return ret;
	}
	
	/**
	 * Get the possible finishing time for today. Takes into account the target work time for the week and also if this
	 * is the last day in the working week.
	 * 
	 * @return {@code null} either if today is not a work day (as defined in the options) or if the regular working time
	 *         for today is already over
	 */
	public DateTime getFinishingTime() {
		DateTime dateTime = DateTimeUtil.getCurrentDateTime();
		WeekDayEnum weekDay = WeekDayEnum.getByValue(dateTime.getWeekDay());
		if (isWorkDay(weekDay)) {
			TimeSum alreadyWorked = null;
			TimeSum target = null;
			if (isFollowedByWorkDay(weekDay)) {
				// not the last work day of the week, only calculate the rest of the daily working time
				alreadyWorked = calculateTimeSum(dateTime, PeriodEnum.DAY);
				int targetMinutes = getNormalWorkDurationFor(weekDay);
				target = new TimeSum();
				target.add(0, targetMinutes);
			} else {
				// the last work day: calculate the rest of the weekly working time
				alreadyWorked = calculateTimeSum(dateTime, PeriodEnum.WEEK);
				String targetValueString = preferences.getString(Key.FLEXI_TIME_TARGET.getName(), "0:00");
				target = parseHoursMinutesString(targetValueString);
			}
			Logger.debug("alreadyWorked={0}", alreadyWorked.toString());
			Logger.debug("target={0}", target.toString());
			
			Logger.debug("isAutoPauseEnabled={0}", isAutoPauseEnabled());
			Logger.debug("isAutoPauseTheoreticallyApplicable={0}", isAutoPauseTheoreticallyApplicable(dateTime));
			Logger.debug("isAutoPauseApplicable={0}", isAutoPauseApplicable(dateTime));
			if (isAutoPauseEnabled() && isAutoPauseTheoreticallyApplicable(dateTime)
				&& !isAutoPauseApplicable(dateTime)) {
				// auto-pause is necessary, but was NOT already taken into account by calculateTimeSum():
				Logger.debug("auto-pause is necessary, but was NOT already taken into account by calculateTimeSum()");
				DateTime autoPauseBegin = getAutoPauseBegin(dateTime);
				DateTime autoPauseEnd = getAutoPauseEnd(dateTime);
				alreadyWorked.substract(autoPauseEnd.getHour(), autoPauseEnd.getMinute());
				alreadyWorked.add(autoPauseBegin.getHour(), autoPauseBegin.getMinute());
			}
			int minutesRemaining = target.getAsMinutes() - alreadyWorked.getAsMinutes();
			Logger.debug("minutesRemaining={0}", minutesRemaining);
			
			if (minutesRemaining >= 0) {
				return dateTime.plus(0, 0, 0, 0, minutesRemaining, 0, DayOverflow.Spillover);
			} else {
				return null;
			}
		} else {
			return null;
		}
	}
	
	/**
	 * Get the flexi-time balance which is effective at the given week start.
	 */
	public TimeSum getFlexiBalanceAtWeekStart(String weekStart) {
		TimeSum ret = new TimeSum();
		String startValueString = preferences.getString(Key.FLEXI_TIME_START_VALUE.getName(), "0:00");
		String targetWorkTimeString = preferences.getString(Key.FLEXI_TIME_TARGET.getName(), "0:00");
		TimeSum startValue = parseHoursMinutesString(startValueString);
		TimeSum targetWorkTime = parseHoursMinutesString(targetWorkTimeString);
		ret.addOrSubstract(startValue);
		
		DateTime upTo = DateTimeUtil.stringToDateTime(weekStart).minusDays(1);
		List<Week> weeksToCount = dao.getWeeksUpTo(DateTimeUtil.dateTimeToString(upTo));
		
		for (Week week : weeksToCount) {
			Integer weekWorkedMinutes = week.getSum();
			if (weekWorkedMinutes != null) {
				// add the actual work time
				ret.add(0, weekWorkedMinutes);
				// substract the target work time
				ret.substract(0, targetWorkTime.getAsMinutes());
			} else {
				Logger.warn("week {0} (starting at {1}) has a null sum", week.getId(), week.getStart());
			}
		}
		
		return ret;
	}
	
	/**
	 * Parse a value of hours and minutes (positive or negative).
	 */
	public static TimeSum parseHoursMinutesString(String hoursMinutes) {
		TimeSum ret = new TimeSum();
		if (hoursMinutes != null) {
			String[] startValueArray = hoursMinutes.split(":");
			int hours = Integer.parseInt(startValueArray[0]);
			int minutes = startValueArray.length > 1 ? Integer.parseInt(startValueArray[1]) : 0;
			if (hoursMinutes.trim().startsWith("-")) {
				ret.substract(-1 * hours, minutes);
			} else {
				ret.add(hours, minutes);
			}
		}
		return ret;
	}
	
	/**
	 * Get the normal work time (in minutes) for a specific week day.
	 */
	public int getNormalWorkDurationFor(WeekDayEnum weekDay) {
		if (isWorkDay(weekDay)) {
			String targetValueString = preferences.getString(Key.FLEXI_TIME_TARGET.getName(), "0:00");
			targetValueString = DateTimeUtil.refineHourMinute(targetValueString);
			TimeSum targetValue = parseHoursMinutesString(targetValueString);
			BigDecimal minutes = new BigDecimal(targetValue.getAsMinutes()).divide(new BigDecimal(countWorkDays()));
			MathContext mc = new MathContext(1, RoundingMode.HALF_UP);
			minutes.round(mc);
			return minutes.intValue();
		} else {
			return 0;
		}
	}
	
	private int countWorkDays() {
		int ret = 0;
		for (WeekDayEnum day : WeekDayEnum.values()) {
			if (isWorkDay(day)) {
				ret++;
			}
		}
		return ret;
	}
	
	/**
	 * Is there a day in the week after the given day which is also marked as work day? That means, is the given day NOT
	 * the last work day in the week?
	 */
	private boolean isFollowedByWorkDay(WeekDayEnum day) {
		WeekDayEnum nextDay = day.getNextWeekDay();
		while (nextDay != null) {
			if (isWorkDay(nextDay)) {
				return true;
			}
			nextDay = nextDay.getNextWeekDay();
		}
		return false;
	}
	
	/**
	 * Is today a work day?
	 */
	public boolean isTodayWorkDay() {
		DateTime dateTime = DateTimeUtil.getCurrentDateTime();
		WeekDayEnum weekDay = WeekDayEnum.getByValue(dateTime.getWeekDay());
		return isWorkDay(weekDay);
	}
	
	/**
	 * Is this a work day?
	 */
	public boolean isWorkDay(WeekDayEnum weekDay) {
		Key key = null;
		switch (weekDay) {
			case MONDAY:
				key = Key.FLEXI_TIME_DAY_MONDAY;
				break;
			case TUESDAY:
				key = Key.FLEXI_TIME_DAY_TUESDAY;
				break;
			case WEDNESDAY:
				key = Key.FLEXI_TIME_DAY_WEDNESDAY;
				break;
			case THURSDAY:
				key = Key.FLEXI_TIME_DAY_THURSDAY;
				break;
			case FRIDAY:
				key = Key.FLEXI_TIME_DAY_FRIDAY;
				break;
			case SATURDAY:
				key = Key.FLEXI_TIME_DAY_SATURDAY;
				break;
			case SUNDAY:
				key = Key.FLEXI_TIME_DAY_SUNDAY;
				break;
			default:
				throw new IllegalArgumentException("unknown weekday");
		}
		return preferences.getBoolean(key.getName(), false);
	}
	
	/**
	 * Create a new event at current time + the given minute amount.
	 * 
	 * @param minutesToPredate how many minutes to add to "now"
	 * @param taskId the task id (may be {@code null})
	 * @param type the type
	 * @param text the text (may be {@code null})
	 */
	public void createEvent(int minutesToPredate, Integer taskId, TypeEnum type, String text) {
		if (minutesToPredate < 0) {
			throw new IllegalArgumentException("no negative minute amount allowed");
		}
		DateTime targetTime = DateTimeUtil.getCurrentDateTime();
		targetTime = targetTime.plus(0, 0, 0, 0, minutesToPredate, 0, DayOverflow.Spillover);
		createEvent(targetTime, taskId, type, text);
	}
	
	/**
	 * Create a new event at the given time.
	 * 
	 * @param dateTime the time for which the new event should be created
	 * @param taskId the task id (may be {@code null})
	 * @param type the type
	 * @param text the text (may be {@code null})
	 */
	public void createEvent(DateTime dateTime, Integer taskId, TypeEnum type, String text) {
		if (dateTime == null) {
			throw new IllegalArgumentException("date/time has to be given");
		}
		if (type == null) {
			throw new IllegalArgumentException("type has to be given");
		}
		String weekStart = DateTimeUtil.getWeekStartAsString(dateTime);
		String time = DateTimeUtil.dateTimeToString(dateTime);
		Week currentWeek = dao.getWeek(weekStart);
		if (currentWeek == null) {
			currentWeek = createPersistentWeek(weekStart);
		}
		
		if (type == TypeEnum.CLOCK_OUT) {
			tryToInsertAutoPause(dateTime);
		}
		
		Event event = new Event(null, currentWeek.getId(), taskId, type.getValue(), time, text);
		Logger.debug("TRACKING: {0} @ {1} taskId={2} text={3}", type.name(), time, taskId, text);
		event = dao.insertEvent(event);
		
		updateWeekSum(currentWeek);
		Basics.getInstance().safeCheckPersistentNotification();
	}
	
	/**
	 * Update the week's total worked sum.
	 */
	public void updateWeekSum(Week week) {
		TimeSum sum = calculateTimeSum(DateTimeUtil.stringToDateTime(week.getStart()), PeriodEnum.WEEK);
		int minutes = sum.getAsMinutes();
		Logger.info("updating the time sum to {0} minutes for the week beginning at {1}", minutes, week.getStart());
		Week weekToUse = week;
		if (week instanceof WeekPlaceholder) {
			weekToUse = createPersistentWeek(week.getStart());
		}
		weekToUse.setSum(minutes);
		dao.updateWeek(weekToUse);
		// TODO update the sum of the last week(s) if type is CLOCK_OUT?
		// TODO update the sum of the next week(s) if type is CLOCK_IN?
	}
	
	private Week createPersistentWeek(String weekStart) {
		Week week = new Week(null, weekStart, 0);
		week = dao.insertWeek(week);
		return week;
	}
	
	/**
	 * Create a new NON-PERSISTENT (!) event of the type CLOCK_OUT_NOW.
	 */
	public Event createClockOutNowEvent() {
		DateTime now = DateTimeUtil.getCurrentDateTime();
		String weekStart = DateTimeUtil.getWeekStartAsString(now);
		Week currentWeek = dao.getWeek(weekStart);
		return new Event(null, (currentWeek == null ? null : currentWeek.getId()), null,
			TypeEnum.CLOCK_OUT_NOW.getValue(), DateTimeUtil.dateTimeToString(now), null);
	}
	
	private void tryToInsertAutoPause(DateTime dateTime) {
		if (isAutoPauseEnabled() && isAutoPauseApplicable(dateTime)) {
			// insert auto-pause events
			DateTime begin = getAutoPauseBegin(dateTime);
			DateTime end = getAutoPauseEnd(dateTime);
			Logger.debug("inserting auto-pause, begin={0}, end={1}", begin, end);
			createEvent(begin, null, TypeEnum.CLOCK_OUT, null);
			Event lastBeforePause = dao.getLastEventBefore(begin);
			createEvent(end, (lastBeforePause == null ? null : lastBeforePause.getTask()), TypeEnum.CLOCK_IN,
				(lastBeforePause == null ? null : lastBeforePause.getText()));
		} else {
			Logger.debug("NOT inserting auto-pause");
		}
	}
	
	/**
	 * Determines if the auto-pause mechanism is enabled.
	 */
	public boolean isAutoPauseEnabled() {
		return preferences.getBoolean(Key.AUTO_PAUSE_ENABLED.getName(), false);
	}
	
	/**
	 * Determines if the auto-pause can be applied to the given day.
	 */
	public boolean isAutoPauseApplicable(DateTime dateTime) {
		DateTime end = getAutoPauseEnd(dateTime);
		// auto-pause is theoretically applicable
		return isAutoPauseTheoreticallyApplicable(dateTime)
		// given time is after auto-pause end, so auto-pause should really be applied
			&& dateTime.gt(end);
	}
	
	private boolean isAutoPauseTheoreticallyApplicable(DateTime dateTime) {
		DateTime begin = getAutoPauseBegin(dateTime);
		DateTime end = getAutoPauseEnd(dateTime);
		if (begin.lt(end)) {
			Event lastEventBeforeBegin = dao.getLastEventBefore(begin);
			Event lastEventBeforeEnd = dao.getLastEventBefore(end);
			// is clocked in before begin
			return lastEventBeforeBegin != null && lastEventBeforeBegin.getType().equals(TypeEnum.CLOCK_IN.getValue())
			// no event is in auto-pause interval
				&& lastEventBeforeBegin.getId().equals(lastEventBeforeEnd.getId());
		} else {
			// begin is equal to end or (even worse) begin is after end => no auto-pause
			return false;
		}
	}
	
	/**
	 * Calculates the begin of the auto-pause for the given day.
	 */
	public DateTime getAutoPauseBegin(DateTime dateTime) {
		return DateTimeUtil.parseTimeFor(dateTime, getAutoPauseData(Key.AUTO_PAUSE_BEGIN.getName(), "23.59"));
	}
	
	/**
	 * Calculates the end of the auto-pause for the given day.
	 */
	public DateTime getAutoPauseEnd(DateTime dateTime) {
		return DateTimeUtil.parseTimeFor(dateTime, getAutoPauseData(Key.AUTO_PAUSE_END.getName(), "00.00"));
	}
	
	private String getAutoPauseData(String key, String defaultTime) {
		String ret = preferences.getString(key, defaultTime);
		ret = DateTimeUtil.refineTime(ret);
		return ret;
	}
	
}
