package org.heavenlyweiner.donatenotify.text;

/** Русские формы множественного числа: «1 день», «2 дня», «5 дней». */
public final class Plural {

    private Plural() {}

    public static String of(long n, String one, String few, String many) {
        long abs = Math.abs(n);
        long mod10 = abs % 10;
        long mod100 = abs % 100;
        String word;
        if (mod10 == 1 && mod100 != 11) word = one;
        else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) word = few;
        else word = many;
        return n + " " + word;
    }

    public static String days(long n) {
        return of(n, "день", "дня", "дней");
    }

    public static String hours(long n) {
        return of(n, "час", "часа", "часов");
    }

    public static String minutes(long n) {
        return of(n, "минута", "минуты", "минут");
    }

    public static String seconds(long n) {
        return of(n, "секунда", "секунды", "секунд");
    }
}
