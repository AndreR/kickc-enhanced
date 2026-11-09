// Tests the C standard library time.h: time_t <-> struct tm conversion, asctime, strftime, difftime and the TOD clock
#include <time.h>
#include <string.h>
#include <conio.h>

unsigned char failures = 0;
unsigned char tests = 0;

void check_str(const char* name, const char* actual, const char* expected) {
    tests++;
    if (strcmp(actual, expected) != 0) {
        failures++;
        cputs("FAIL ");
        cputs(name);
        cputs(": ");
        cputs(actual);
        cputs("\r\n");
    }
}

void check_ulong(const char* name, time_t actual, time_t expected) {
    tests++;
    if (actual != expected) {
        failures++;
        cputs("FAIL ");
        cputs(name);
        cputs("\r\n");
    }
}

void main() {
    char buf[40];
    struct tm tm;
    time_t t;

    // asctime/ctime on the epoch, a leap day, the 2038/2106 edges and the non-leap year 2100
    t = 0;
    check_str("epoch", ctime_r(&t, buf), "Thu Jan  1 00:00:00 1970\n");
    t = 1_000_000_000UL;
    check_str("1e9", ctime_r(&t, buf), "Sun Sep  9 01:46:40 2001\n");
    t = 1_700_000_000UL;
    check_str("1.7e9", ctime_r(&t, buf), "Tue Nov 14 22:13:20 2023\n");
    t = 951_782_400UL;
    check_str("2000-02-29", ctime_r(&t, buf), "Tue Feb 29 00:00:00 2000\n");
    t = 2_147_483_647UL;
    check_str("2038", ctime_r(&t, buf), "Tue Jan 19 03:14:07 2038\n");
    t = 4_107_542_400UL;
    check_str("2100-03-01", ctime_r(&t, buf), "Mon Mar  1 00:00:00 2100\n");
    t = 4_294_967_295UL;
    check_str("max", ctime_r(&t, buf), "Sun Feb  7 06:28:15 2106\n");

    // mktime
    t = 1_700_000_000UL;
    gmtime_r(&t, &tm);
    check_ulong("mktime", mktime(&tm), 1_700_000_000UL);
    check_ulong("yday", tm.tm_yday, 317);
    check_ulong("wday", tm.tm_wday, TM_TUESDAY);
    // Normalization: month 12 of 2023 is January 2024
    tm.tm_mon = 12; tm.tm_mday = 1; tm.tm_hour = 0; tm.tm_min = 0; tm.tm_sec = 0;
    check_ulong("normalize", mktime(&tm), 1_704_067_200UL);
    check_ulong("normalize year", tm.tm_year, 124);
    check_ulong("normalize mon", tm.tm_mon, TM_JANUARY);
    // Out of range
    tm.tm_year = 69;
    check_ulong("before 1970", mktime(&tm), TIME_T_INVALID);

    // difftime
    check_ulong("difftime+", (time_t)difftime(100, 40), 60);
    check_ulong("difftime-", (time_t)difftime(40, 100), (time_t)-60L);

    // strftime
    t = 1_700_000_000UL;
    gmtime_r(&t, &tm);
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &tm);
    check_str("strftime iso", buf, "2023-11-14 22:13:20");
    strftime(buf, sizeof(buf), "%a %A %b %B %j", &tm);
    check_str("strftime names", buf, "Tue Tuesday Nov November 318");
    strftime(buf, sizeof(buf), "%I %p %e %y %C %u %w %%", &tm);
    check_str("strftime misc", buf, "10 PM 14 23 20 2 2 %");
    strftime(buf, sizeof(buf), "%F %T %R %D", &tm);
    check_str("strftime composite", buf, "2023-11-14 22:13:20 22:13 11/14/23");
    check_ulong("strftime overflow", strftime(buf, 5, "%Y-%m-%d", &tm), 0);

    // TOD clock: set and read back
    tod_set_frequency(50);
    set_time(1_700_000_000UL);
    t = time(nullptr);
    tests++;
    if (t < 1_700_000_000UL || t > 1_700_000_002UL) failures++;

    cputs("tests: ");
    cputc('0' + tests / 10);
    cputc('0' + tests % 10);
    cputs(" failures: ");
    cputc('0' + failures);
    cputs("\r\n");
    if (failures == 0) cputs("all ok\r\n");
    while (true) {}
}
