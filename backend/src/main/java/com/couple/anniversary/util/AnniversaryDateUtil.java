package com.couple.anniversary.util;

import java.time.LocalDate;

/**
 * 计算下一个纪念日工具类
 */
public class AnniversaryDateUtil {

    /**
     * 计算下一次到达日期
     * 把月-日套到今年，如果今年已过则套到明年
     */
    public static LocalDate nextOccurrence(LocalDate anniversaryDate, LocalDate today) {
        LocalDate thisYear = withYearSafe(anniversaryDate, today.getYear());
        if (!thisYear.isBefore(today)) {
            return thisYear;
        }
        return withYearSafe(anniversaryDate, today.getYear() + 1);
    }

    /**
     * 2/29 在非闰年时退化为 2/28
     */
    private static LocalDate withYearSafe(LocalDate date, int year) {
        try {
            return date.withYear(year);
        } catch (Exception e) {
            return date.withDayOfMonth(28).withYear(year);
        }
    }
}