// C standard library time.h
//  Functions to get and manipulate date and time information.
//  See time.h for the (6502-specific) differences to ISO C.
#include <time.h>
#include <c64.h>
#include <division.h>
#include <multiply.h>
#include <bcd.h>

static_assert(sizeof(struct tm) == 11, "struct tm layout changed");

// ---------------------------------------------------------------------------------------------
// Processor clock (CIA#2 Timer A+B)
// ---------------------------------------------------------------------------------------------

// Returns the processor clock time used since the beginning of an implementation defined era (normally the beginning of the program).
// This uses CIA #2 Timer A+B on the C64, and must be initialized using clock_start()
clock_t clock(void) {
    // Stop the timer
    CIA2->TIMER_A_CONTROL = CIA_TIMER_CONTROL_STOP | CIA_TIMER_CONTROL_CONTINUOUS | CIA_TIMER_CONTROL_A_COUNT_CYCLES;
    clock_t ticks = 0xffffffff - *CIA2_TIMER_AB;
    // Start the timer
    CIA2->TIMER_A_CONTROL = CIA_TIMER_CONTROL_START | CIA_TIMER_CONTROL_CONTINUOUS | CIA_TIMER_CONTROL_A_COUNT_CYCLES;
    return ticks;
}

// Reset & start the processor clock time. The value can be read using clock().
// This uses CIA #2 Timer A+B on the C64
void clock_start(void) {
    // Setup CIA#2 timer A to count (down) CPU cycles
    CIA2->TIMER_A_CONTROL = CIA_TIMER_CONTROL_STOP | CIA_TIMER_CONTROL_CONTINUOUS | CIA_TIMER_CONTROL_A_COUNT_CYCLES;
    CIA2->TIMER_B_CONTROL = CIA_TIMER_CONTROL_STOP | CIA_TIMER_CONTROL_CONTINUOUS | CIA_TIMER_CONTROL_B_COUNT_UNDERFLOW_A;
    *CIA2_TIMER_AB = 0xffffffff;
    CIA2->TIMER_B_CONTROL = CIA_TIMER_CONTROL_START | CIA_TIMER_CONTROL_CONTINUOUS | CIA_TIMER_CONTROL_B_COUNT_UNDERFLOW_A;
    CIA2->TIMER_A_CONTROL = CIA_TIMER_CONTROL_START | CIA_TIMER_CONTROL_CONTINUOUS | CIA_TIMER_CONTROL_A_COUNT_CYCLES;
}

// ---------------------------------------------------------------------------------------------
// Constant tables (flat 1D tables allow absolute-indexed access)
// ---------------------------------------------------------------------------------------------

// Days in each month of a non-leap year
const uint8_t MONTH_DAYS[12] = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
static_assert(countof(MONTH_DAYS) == 12);

// Days from January 1 to the first of each month in a non-leap year
__lohi const uint16_t DAYS_BEFORE_MONTH[12] = { 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334 };
static_assert(countof(DAYS_BEFORE_MONTH) == countof(MONTH_DAYS));

// 3-letter abbreviations, concatenated: entry i starts at i*3
const char WDAY_ABBR[] = "SunMonTueWedThuFriSat";
const char MON_ABBR[]  = "JanFebMarAprMayJunJulAugSepOctNovDec";

// Full names
const char* const WDAY_NAMES[7] = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
const char* const MON_NAMES[12] = { "January", "February", "March", "April", "May", "June",
                                    "July", "August", "September", "October", "November", "December" };

// Days from 1970-01-01 to 2000-01-01 and the number of days in the years 2000..2099 (2100 is no leap year)
#define DAYS_1970_TO_2000 10957
#define DAYS_2000_TO_2100 36525
// Days in 4 years starting with a leap year
#define DAYS_PER_4_YEARS  1461
// Days from 1970-01-01 to the last day representable in a 32-bit time_t (2106-02-07)
#define DAYS_MAX          49710UL

// ---------------------------------------------------------------------------------------------
// TOD clock (CIA#1) and time()
// ---------------------------------------------------------------------------------------------

// Results of split_days() / split_hms()
static uint32_t split_sod;
static uint8_t split_h;
static uint8_t split_m;
static uint8_t split_s;

// Splits t into the number of whole days since the epoch (returned) and the seconds of that day (split_sod).
// 86400 = 675 * 128, so the 32-bit division is done as a 32/16-bit division of t>>7.
static uint16_t split_days(time_t t) {
    uint16_t days = (uint16_t)div32u16u(t >> 7, 675);
    split_sod = ((uint32_t)rem16u << 7) | (t & 127);
    return days;
}

// Splits seconds of a day into split_h, split_m and split_s
static void split_hms(uint32_t sod) {
    split_h = (uint8_t)div32u16u(sod, 3600);
    split_m = (uint8_t)div16u(rem16u, 60);
    split_s = (uint8_t)rem16u;
}

// Epoch seconds at the most recent midnight of the TOD clock
static time_t time_day_base = 0;
// Seconds since midnight at the last time() call; used to detect midnight rollover
static uint32_t time_last_sod = 0;

// Returns the current calendar time as seconds since the epoch, read from the CIA#1 TOD clock.
time_t time(time_t* timer) {
    // Reading the hours latches the clock, reading the tenths releases the latch again
    uint8_t h = CIA1->TOD_HOURS;
    uint8_t m = CIA1->TOD_MIN;
    uint8_t s = CIA1->TOD_SEC;
    asm(clobbers "A") { lda $dc08 }
    uint8_t hr = bcd8_to_bin(h & 0x1f);
    if (hr == 12) hr = 0;
    if (h & 0x80) hr += 12;
    uint32_t sod = mul16u(hr, SECS_PER_HOUR);
    sod += (uint16_t)bcd8_to_bin(m) * SECS_PER_MINUTE;
    sod += bcd8_to_bin(s);
    if (sod < time_last_sod) time_day_base += SECS_PER_DAY;
    time_last_sod = sod;
    time_t now = time_day_base + sod;
    if (timer != nullptr) *timer = now;
    return now;
}

// Sets the calendar time and (re)starts the CIA#1 TOD clock at that time.
void set_time(time_t t) {
    split_days(t);
    uint32_t sod = split_sod;
    time_day_base = t - sod;
    time_last_sod = sod;
    split_hms(sod);
    uint8_t h = split_h;
    uint8_t m = split_m;
    uint8_t s = split_s;
    // The TOD registers are 12-hour BCD with the PM flag in bit 7
    uint8_t h12 = h % 12;
    if (h12 == 0) h12 = 12;
    uint8_t hb = bin_to_bcd8(h12);
    if (h >= 12) hb |= 0x80;
    // Make sure the TOD registers (not the alarm) are written
    CIA1->TIMER_B_CONTROL &= 0x7f;
    // Writing the hours stops the clock, writing the tenths starts it
    CIA1->TOD_HOURS = hb;
    CIA1->TOD_MIN = bin_to_bcd8(m);
    CIA1->TOD_SEC = bin_to_bcd8(s);
    CIA1->TOD_10THS = 0;
}

// Configures the CIA#1 TOD clock for the mains frequency (50 or 60).
void tod_set_frequency(uint8_t hz) {
    uint8_t cra = CIA1->TIMER_A_CONTROL & 0x7f;
    if (hz == 50) cra |= CIA_TIMER_CONTROL_A_TOD_50HZ;
    CIA1->TIMER_A_CONTROL = cra;
}

// Returns the difference end - beginning in seconds.
int32_t difftime(time_t end, time_t beginning) {
    if (end >= beginning) return (int32_t)(end - beginning);
    return -(int32_t)(beginning - end);
}

// ---------------------------------------------------------------------------------------------
// Calendar calculations
// ---------------------------------------------------------------------------------------------

// Returns true if year is a leap year in the Gregorian calendar.
__carry bool is_leap_year(uint16_t year) {
    if ((year & 3) != 0) return false;
    uint16_t century = div16u(year, 100);
    if (rem16u != 0) return true;
    // A century year is a leap year only if the century is divisible by 4
    return (century & 3) == 0;
}

// Returns the number of days in month mon [0, 11] of the year.
uint8_t days_in_month(uint16_t year, uint8_t mon) {
    uint8_t d = MONTH_DAYS[mon];
    if (mon == TM_FEBRUARY && is_leap_year(year)) d++;
    return d;
}

// Number of days from 1970-01-01 to January 1 of the given year (1970 or later)
static uint16_t days_before_year(uint16_t year) {
    uint16_t y = year - 1;
    uint16_t centuries = div16u(y, 100);
    // 477 leap days occurred up to and including 1969
    return (uint16_t)(year - 1970) * 365 + (y >> 2) - centuries + (centuries >> 2) - 477;
}

// Converts calendar time to broken-down UTC time into the caller's struct.
struct tm* gmtime_r(const time_t* timer, struct tm* result) {
    time_t t = *timer;
    uint16_t days = split_days(t);
    split_hms(split_sod);
    result->tm_hour = split_h;
    result->tm_min = split_m;
    result->tm_sec = split_s;
    // 1970-01-01 was a Thursday
    div16u(days + 4, 7);
    result->tm_wday = (uint8_t)rem16u;
    // Find the year: jump to 2000 / 2100 and over blocks of 4 years, then step year by year
    uint16_t year = 1970;
    if (days >= DAYS_1970_TO_2000) {
        days -= DAYS_1970_TO_2000;
        year = 2000;
        if (days >= DAYS_2000_TO_2100) {
            days -= DAYS_2000_TO_2100;
            year = 2100;
        } else {
            uint8_t q = (uint8_t)div16u(days, DAYS_PER_4_YEARS);
            days = rem16u;
            year += (uint16_t)q * 4;
        }
    }
    while (true) {
        if (uint16_t yl = is_leap_year(year) ? (uint16_t)366 : (uint16_t)365; days >= yl) {
            days -= yl;
            year++;
        } else break;
    }
    result->tm_year = year - 1900;
    result->tm_yday = days;
    // Find the month
    bool leap = is_leap_year(year);
    uint8_t mon = 0;
    while (true) {
        uint8_t dim = MONTH_DAYS[mon];
        if (mon == TM_FEBRUARY && leap) dim++;
        if (days < dim) break;
        days -= dim;
        mon++;
    }
    result->tm_mon = mon;
    result->tm_mday = (uint8_t)days + 1;
    result->tm_isdst = 0;
    return result;
}

// Static result buffer of gmtime() / localtime()
static struct tm gm_buf;

// Converts calendar time to broken-down UTC time in a static buffer.
struct tm* gmtime(const time_t* timer) {
    return gmtime_r(timer, &gm_buf);
}

// There are no time zones: local time equals UTC.
struct tm* localtime_r(const time_t* timer, struct tm* result) {
    return gmtime_r(timer, result);
}

// There are no time zones: local time equals UTC.
struct tm* localtime(const time_t* timer) {
    return gmtime_r(timer, &gm_buf);
}

// Converts broken-down time (interpreted as UTC) to calendar time and normalizes the fields.
time_t mktime(struct tm* tp) {
    uint16_t year = tp->tm_year + 1900;
    uint8_t mon = tp->tm_mon;
    // Normalize months beyond December
    year += mon / 12;
    mon = mon % 12;
    if (year < 1970 || year > 2106) return TIME_T_INVALID;
    uint32_t days = days_before_year(year);
    days += DAYS_BEFORE_MONTH[mon];
    if (mon >= TM_MARCH && is_leap_year(year)) days++;
    days += (uint32_t)tp->tm_mday;
    days--;
    if (days > DAYS_MAX) return TIME_T_INVALID;
    // days * 86400 = days * 675 * 128 (explicit 16x16->32 bit multiplication, the product exceeds 16 bits)
    time_t base = mul16u((uint16_t)days, 675) << 7;
    time_t secs = mul16u(tp->tm_hour, SECS_PER_HOUR);
    secs += (uint16_t)tp->tm_min * SECS_PER_MINUTE;
    secs += tp->tm_sec;
    time_t t = base + secs;
    // Overflow of the 32-bit time_t
    if (t < base) return TIME_T_INVALID;
    // Normalize all fields and set tm_wday and tm_yday
    gmtime_r(&t, tp);
    return t;
}

// Same as mktime(); there are no time zones.
time_t timegm(struct tm* tp) {
    return mktime(tp);
}

// ---------------------------------------------------------------------------------------------
// Text formatting
// ---------------------------------------------------------------------------------------------

// Converts a number to 4-digit BCD (values above 9999 are clamped). Needs a single division.
static bcd16_t bin_to_bcd16(uint16_t v) {
    if (v > 9999) v = 9999;
    uint8_t hi = (uint8_t)div16u(v, 100);
    uint8_t lo = (uint8_t)rem16u;
    return ((bcd16_t)bin_to_bcd8(hi) << 8) | bin_to_bcd8(lo);
}

// Writes a 2-digit BCD number with exactly two characters. The leading zero is replaced by pad.
static char* put_bcd8(char* p, bcd8_t b, char pad) {
    uint8_t tens = b >> 4;
    *p++ = (tens == 0) ? pad : (char)('0' + tens);
    *p++ = (char)('0' + (b & 0x0f));
    return p;
}

// Writes a number 0..99 with exactly two characters. The leading zero is replaced by pad.
static char* put2(char* p, uint8_t v, char pad) {
    return put_bcd8(p, bin_to_bcd8(v), pad);
}

// Writes a year 0..9999 with four digits
static char* put4(char* p, uint16_t v) {
    bcd16_t b = bin_to_bcd16(v);
    p = put_bcd8(p, (bcd8_t)(b >> 8), '0');
    return put_bcd8(p, (bcd8_t)b, '0');
}

// Writes an abbreviation from a table of 3-letter entries
static char* put3(char* p, const char* table, uint8_t idx) {
    const char* e = table + idx * 3;
    *p++ = e[0];
    *p++ = e[1];
    *p++ = e[2];
    return p;
}

// Converts broken-down time to the string "Www Mmm dd hh:mm:ss yyyy\n".
char* asctime_r(const struct tm* tp, char* buf) {
    uint8_t wd = tp->tm_wday;
    uint8_t mn = tp->tm_mon;
    if (wd >= countof(WDAY_NAMES)) wd = 0;
    if (mn >= countof(MON_NAMES)) mn = 0;
    char* p = put3(buf, WDAY_ABBR, wd);
    *p++ = ' ';
    p = put3(p, MON_ABBR, mn);
    *p++ = ' ';
    p = put2(p, tp->tm_mday, ' ');
    *p++ = ' ';
    p = put2(p, tp->tm_hour, '0');
    *p++ = ':';
    p = put2(p, tp->tm_min, '0');
    *p++ = ':';
    p = put2(p, tp->tm_sec, '0');
    *p++ = ' ';
    p = put4(p, tp->tm_year + 1900);
    *p++ = '\n';
    *p = 0;
    return buf;
}

// Static result buffer of asctime() / ctime()
static char asctime_buf[ASCTIME_BUFFER_SIZE];

// Converts broken-down time to a string in a static buffer.
char* asctime(const struct tm* tp) {
    return asctime_r(tp, asctime_buf);
}

// Converts calendar time to a string.
char* ctime_r(const time_t* timer, char* buf) {
    struct tm t;
    return asctime_r(gmtime_r(timer, &t), buf);
}

// Converts calendar time to a string in a static buffer.
char* ctime(const time_t* timer) {
    return asctime_r(gmtime_r(timer, &gm_buf), asctime_buf);
}

// State of the running strftime() call
static struct { char* ptr; size_t left; bool ok; } sf;

// Appends one character to the strftime() output
static void sf_char(char c) {
    if (sf.left != 0) {
        *sf.ptr++ = c;
        sf.left--;
    } else sf.ok = false;
}

// Appends a zero-terminated string
static void sf_str(const char* s) {
    while (*s) sf_char(*s++);
}

// Appends the number v right-aligned to at least width characters, filled with pad
static void sf_num(uint16_t v, uint8_t width, char pad) {
    // Convert to BCD (a single division) and print the digits by nibble, skipping leading zeros
    bcd16_t b = bin_to_bcd16(v);
    uint8_t d[4];
    d[0] = (uint8_t)(b >> 12);
    d[1] = (uint8_t)((b >> 8) & 0x0f);
    d[2] = (uint8_t)((b >> 4) & 0x0f);
    d[3] = (uint8_t)(b & 0x0f);
    uint8_t first = 0;
    while (first < 3 && d[first] == 0) first++;
    for (uint8_t i = 4 - first; i < width; i++) sf_char(pad);
    for (uint8_t i = first; i < 4; i++) sf_char((char)('0' + d[i]));
}

// Appends a 3-letter abbreviation from a table of 3-letter entries
static void sf_abbr(const char* table, uint8_t idx) {
    const char* e = table + idx * 3;
    sf_char(e[0]);
    sf_char(e[1]);
    sf_char(e[2]);
}

// Appends the time as "HH:MM:SS" (%T), or "HH:MM" (%R) if withSeconds is false
static void sf_time(const struct tm* tp, bool withSeconds) {
    sf_num(tp->tm_hour, 2, '0');
    sf_char(':');
    sf_num(tp->tm_min, 2, '0');
    if (withSeconds) {
        sf_char(':');
        sf_num(tp->tm_sec, 2, '0');
    }
}

// Returns year modulo 100
static uint8_t year_mod100(uint16_t year) {
    div16u(year, 100);
    return (uint8_t)rem16u;
}

// Appends the date as "MM/DD/YY" (%D)
static void sf_date_us(const struct tm* tp) {
    sf_num(tp->tm_mon + 1, 2, '0');
    sf_char('/');
    sf_num(tp->tm_mday, 2, '0');
    sf_char('/');
    sf_num(year_mod100(tp->tm_year + 1900), 2, '0');
}

// Formats broken-down time according to format.
size_t strftime(char* s, size_t maxsize, const char* format, const struct tm* tp) {
    if (maxsize == 0) return 0;
    sf.ptr = s;
    sf.left = maxsize - 1;
    sf.ok = true;
    // Range-checked indices for the name tables
    uint8_t wd = tp->tm_wday;
    uint8_t mn = tp->tm_mon;
    if (wd >= countof(WDAY_NAMES)) wd = 0;
    if (mn >= countof(MON_NAMES)) mn = 0;
    uint16_t year = tp->tm_year + 1900;
    while (*format != 0 && sf.ok) {
        char c = *format++;
        if (c != '%') {
            sf_char(c);
            continue;
        }
        char f = *format;
        if (f == 0) {
            sf_char('%');
            break;
        }
        format++;
        switch (f) {
            case 'a': sf_abbr(WDAY_ABBR, wd); break;
            case 'A': sf_str(WDAY_NAMES[wd]); break;
            case 'b':
            case 'h': sf_abbr(MON_ABBR, mn); break;
            case 'B': sf_str(MON_NAMES[mn]); break;
            case 'c':
                sf_abbr(WDAY_ABBR, wd);
                sf_char(' ');
                sf_abbr(MON_ABBR, mn);
                sf_char(' ');
                sf_num(tp->tm_mday, 2, ' ');
                sf_char(' ');
                sf_time(tp, true);
                sf_char(' ');
                sf_num(year, 1, '0');
                break;
            case 'C': sf_num(div16u(year, 100), 2, '0'); break;
            case 'd': sf_num(tp->tm_mday, 2, '0'); break;
            case 'D':
            case 'x': sf_date_us(tp); break;
            case 'e': sf_num(tp->tm_mday, 2, ' '); break;
            case 'F':
                sf_num(year, 4, '0');
                sf_char('-');
                sf_num(tp->tm_mon + 1, 2, '0');
                sf_char('-');
                sf_num(tp->tm_mday, 2, '0');
                break;
            case 'H': sf_num(tp->tm_hour, 2, '0'); break;
            case 'I':
                if (uint8_t h12 = tp->tm_hour % 12; h12 == 0) sf_num(12, 2, '0');
                else sf_num(h12, 2, '0');
                break;
            case 'j': sf_num(tp->tm_yday + 1, 3, '0'); break;
            case 'm': sf_num(tp->tm_mon + 1, 2, '0'); break;
            case 'M': sf_num(tp->tm_min, 2, '0'); break;
            case 'n': sf_char('\n'); break;
            case 'p': sf_str(tp->tm_hour >= 12 ? "PM" : "AM"); break;
            case 'R': sf_time(tp, false); break;
            case 'S': sf_num(tp->tm_sec, 2, '0'); break;
            case 't': sf_char((char)9); break;
            case 'T':
            case 'X': sf_time(tp, true); break;
            case 'u': sf_num(wd == 0 ? 7 : wd, 1, '0'); break;
            case 'w': sf_num(wd, 1, '0'); break;
            case 'y': sf_num(year_mod100(year), 2, '0'); break;
            case 'Y': sf_num(year, 1, '0'); break;
            case '%': sf_char('%'); break;
            default:
                sf_char('%');
                sf_char(f);
                break;
        }
    }
    if (!sf.ok) {
        s[0] = 0;
        return 0;
    }
    *sf.ptr = 0;
    return maxsize - 1 - sf.left;
}
