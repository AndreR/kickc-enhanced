/// @file
/// C standard library time.h
///
/// Functions to get and manipulate date and time information.
///
/// Differences to ISO C that follow from the 6502 target (no floating point, no OS, no time zones):
/// - `time_t` is an *unsigned* 32-bit number of seconds since 1970-01-01 00:00:00 UTC (valid until 2106-02-07).
/// - `difftime()` returns the difference as a signed `long` number of seconds instead of a `double`.
/// - `struct tm` uses 8-bit fields where the value range allows it. Fields must not be negative,
///   so `mktime()` can normalize overflowing (but not underflowing) fields.
/// - There is no time zone support: `localtime()` is identical to `gmtime()` and `tm_isdst` is always 0.
/// - `time()` is driven by the CIA#1 time-of-day (TOD) clock. Set it with `set_time()` first.
#ifndef _TIME_H
#define _TIME_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

/// Type suitable for storing the processor time.
typedef unsigned long clock_t;

/// Clock cycles per frame (on a C64 PAL)
const unsigned int CLOCKS_PER_FRAME = 19656;

/// Frames per second (on a C64 PAL)
const char FRAMES_PER_SEC = 60;

/// Clock cycles per second (on a C64 PAL)
const clock_t CLOCKS_PER_SEC = CLOCKS_PER_FRAME*FRAMES_PER_SEC;

/// Clock cycles used to start & read the cycle clock by calling clock_start() and clock() once. Can be subtracted when calculating the number of cycles used by a routine.
/// To make precise cycle measurements interrupts and the display must be disabled so neither steals any cycles from the code.
const unsigned long CLOCKS_PER_INIT = 0x12;

/// Returns the processor clock time used since the beginning of an implementation defined era (normally the beginning of the program).
/// This uses CIA #2 Timer A+B on the C64, and must be initialized using clock_start()
clock_t clock(void);

/// Reset & start the processor clock time. The value can be read using clock().
/// This uses CIA #2 Timer A+B on the C64
void clock_start(void);

/// Arithmetic type capable of representing calendar time: seconds since 1970-01-01 00:00:00 UTC.
typedef uint32_t time_t;

/// Value returned by `time()` and `mktime()` when the time can not be represented.
#define TIME_T_INVALID 0xFFFFFFFFUL

/// Seconds per minute / hour / day
#define SECS_PER_MINUTE 60
#define SECS_PER_HOUR   3_600
#define SECS_PER_DAY    86_400UL

/// Broken-down calendar time.
struct tm {
    /// Seconds after the minute [0, 60] (60 allows for a leap second)
    uint8_t  tm_sec;
    /// Minutes after the hour [0, 59]
    uint8_t  tm_min;
    /// Hours since midnight [0, 23]
    uint8_t  tm_hour;
    /// Day of the month [1, 31]
    uint8_t  tm_mday;
    /// Months since January [0, 11]
    uint8_t  tm_mon;
    /// Days since Sunday [0, 6]
    uint8_t  tm_wday;
    /// Daylight saving time flag (always 0, no time zone support)
    int8_t   tm_isdst;
    /// Years since 1900
    uint16_t tm_year;
    /// Days since January 1 [0, 365]
    uint16_t tm_yday;
};

/// Month numbers for `tm_mon`
enum : uint8_t {
    TM_JANUARY, TM_FEBRUARY, TM_MARCH, TM_APRIL, TM_MAY, TM_JUNE,
    TM_JULY, TM_AUGUST, TM_SEPTEMBER, TM_OCTOBER, TM_NOVEMBER, TM_DECEMBER
};

/// Weekday numbers for `tm_wday`
enum : uint8_t {
    TM_SUNDAY, TM_MONDAY, TM_TUESDAY, TM_WEDNESDAY, TM_THURSDAY, TM_FRIDAY, TM_SATURDAY
};

/// Size of the buffer needed by `asctime_r()` and `ctime_r()` (25 characters + zero-termination)
#define ASCTIME_BUFFER_SIZE 26

/// Returns the current calendar time as seconds since the epoch, read from the CIA#1 TOD clock.
/// The clock must have been set using `set_time()`, otherwise only the time of day since power-on is returned.
/// Midnight rollovers are detected, so the function has to be called at least once every 24 hours.
/// @param timer If not nullptr the result is also stored here
time_t time(time_t* timer);

/// Sets the calendar time and (re)starts the CIA#1 TOD clock at that time.
/// The TOD clock is clocked by the mains frequency, see `tod_set_frequency()`.
void set_time(time_t t);

/// Configures the CIA#1 TOD clock for the mains frequency (50 for PAL machines, 60 for NTSC machines).
/// The default after reset depends on the KERNAL setup, so call this before `set_time()`.
void tod_set_frequency(uint8_t hz);

/// Returns the difference `end - beginning` in seconds.
/// (ISO C returns a `double`, which does not exist in KickC)
int32_t difftime(time_t end, time_t beginning);

/// Returns true if `year` (full year, e.g. 2026) is a leap year in the Gregorian calendar.
__carry bool is_leap_year(uint16_t year);

/// Returns the number of days in the month `mon` [0, 11] of the full year `year`.
uint8_t days_in_month(uint16_t year, uint8_t mon);

/// Converts calendar time to broken-down UTC time into the caller's struct. Returns `result`.
struct tm* gmtime_r(const time_t* timer, struct tm* result);

/// Converts calendar time to broken-down UTC time in a static buffer (overwritten by each call).
struct tm* gmtime(const time_t* timer);

/// Converts calendar time to broken-down local time. There are no time zones, so this equals `gmtime_r()`.
struct tm* localtime_r(const time_t* timer, struct tm* result);

/// Converts calendar time to broken-down local time in a static buffer. Equals `gmtime()`.
struct tm* localtime(const time_t* timer);

/// Converts broken-down time (interpreted as UTC) to calendar time.
/// The fields `tm_wday` and `tm_yday` are ignored. All other fields are normalized into their usual ranges
/// (e.g. `tm_mon = 12` becomes January of the next year) and `tm_wday` / `tm_yday` are filled in.
/// @return the calendar time, or `TIME_T_INVALID` if it is before 1970 or after 2106.
time_t mktime(struct tm* tp);

/// Same as `mktime()`; there are no time zones.
time_t timegm(struct tm* tp);

/// Converts broken-down time to the string "Www Mmm dd hh:mm:ss yyyy\n" into the buffer `buf`
/// (at least `ASCTIME_BUFFER_SIZE` chars). Returns `buf`.
char* asctime_r(const struct tm* tp, char* buf);

/// Converts broken-down time to a string in a static buffer (overwritten by each call).
char* asctime(const struct tm* tp);

/// Converts calendar time to a string like `asctime(gmtime(timer))` into the buffer `buf`.
char* ctime_r(const time_t* timer, char* buf);

/// Converts calendar time to a string like `asctime(localtime(timer))` in a static buffer.
char* ctime(const time_t* timer);

/// Formats broken-down time into the string `s` of size `maxsize` according to `format`.
/// Supported conversions: `%a %A %b %B %h %c %C %d %D %e %F %H %I %j %m %M %n %p %R %S %t %T %u %w %x %X %y %Y %%`
/// (the C locale is assumed). Unknown conversions are copied unchanged.
/// @return the number of characters written excluding the zero-termination, or 0 if the result did not fit.
size_t strftime(char* s, size_t maxsize, const char* format, const struct tm* tp);

#endif
