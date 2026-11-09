// Tests the C standard library time.h: time_t <-> struct tm conversion, asctime, strftime, difftime and the TOD clock
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
/// NULL pointer
  // Commodore 64 PRG executable file
.file [name="time.prg", type="prg", segments="Program"]
.segmentdef Program [segments="Basic, Code, Data"]
.segmentdef Basic [start=$0801]
.segmentdef Code [start=$80d]
.segmentdef Data [startAfter="Code"]
.segmentdef StackRam [start=$0100, min=$0100, max=$01af]
.segment Basic
:BasicUpstart(__start)
  .const TM_JANUARY = 0
  .const TM_FEBRUARY = 1
  .const TM_MARCH = 2
  .const TM_TUESDAY = 2
  /// Timer A Control - time-of-day clock Mode (0: 60Hz, 1: 50Hz)
  .const CIA_TIMER_CONTROL_A_TOD_50HZ = $80
  .const LIGHT_BLUE = $e
  .const SIZEOF_STRUCT_TM = $b
  .const SIZEOF_CHAR = 1
  .const OFFSET_STRUCT_MOS6526_CIA_TIMER_A_CONTROL = $e
  .const OFFSET_STRUCT_MOS6526_CIA_TIMER_B_CONTROL = $f
  .const OFFSET_STRUCT_MOS6526_CIA_TOD_HOURS = $b
  .const OFFSET_STRUCT_MOS6526_CIA_TOD_MIN = $a
  .const OFFSET_STRUCT_MOS6526_CIA_TOD_SEC = 9
  .const OFFSET_STRUCT_MOS6526_CIA_TOD_10THS = 8
  .const OFFSET_STRUCT_TM_TM_HOUR = 2
  .const OFFSET_STRUCT_TM_TM_MIN = 1
  .const OFFSET_STRUCT_TM_TM_WDAY = 5
  .const OFFSET_STRUCT_TM_TM_YEAR = 7
  .const OFFSET_STRUCT_TM_TM_YDAY = 9
  .const OFFSET_STRUCT_TM_TM_MON = 4
  .const OFFSET_STRUCT_TM_TM_MDAY = 3
  .const OFFSET_STRUCT_TM_TM_ISDST = 6
  .const SIZEOF_STRUCT___4 = 5
  /// Color Ram
  .label COLORRAM = $d800
  /// Default address of screen character matrix
  .label DEFAULT_SCREEN = $400
  /// The CIA#1: keyboard matrix, joystick #1/#2
  .label CIA1 = $dc00
  // The number of bytes on the screen
  // The current cursor x-position
  .label conio_cursor_x = $41
  // The current cursor y-position
  .label conio_cursor_y = $2a
  // The current text cursor line start
  .label conio_line_text = $33
  // The current color cursor line start
  .label conio_line_color = $31
  // Epoch seconds at the most recent midnight of the TOD clock
  .label time_day_base = $58
  .label tests = $5c
  .label failures = $5d
  // Seconds since midnight at the last time() call; used to detect midnight rollover
  .label time_last_sod = $7e
  // Remainder after unsigned 16-bit division
  .label rem16u = $24
  // Results of split_days() / split_hms()
  .label split_sod = $64
  .label split_h = $70
  .label split_m = $62
  .label split_s = $63
.segment Code
__start: {
    // __ma char conio_cursor_x = 0
    lda #0
    sta.z conio_cursor_x
    // __ma char conio_cursor_y = 0
    sta.z conio_cursor_y
    // __ma char *conio_line_text = CONIO_SCREEN_TEXT
    lda #<DEFAULT_SCREEN
    sta.z conio_line_text
    lda #>DEFAULT_SCREEN
    sta.z conio_line_text+1
    // __ma char *conio_line_color = CONIO_SCREEN_COLORS
    lda #<COLORRAM
    sta.z conio_line_color
    lda #>COLORRAM
    sta.z conio_line_color+1
    // #pragma constructor_for(conio_c64_init, cputc, clrscr, cscroll)
    jsr conio_c64_init
    jsr main
    rts
}
// Set initial cursor position
conio_c64_init: {
    // Position cursor at current line
    .label BASIC_CURSOR_LINE = $d6
    // char line = *BASIC_CURSOR_LINE
    ldx.z BASIC_CURSOR_LINE
    // if(line>=CONIO_HEIGHT)
    cpx #$19
    bcc __b1
    ldx #$19-1
    jmp gotoxy
  __b1:
    // gotoxy(0, line)
    jmp gotoxy
    // }
}
main: {
    .label tm = $af
    .label t = $75
    .label __25 = $49
    .label __27 = $49
    .label __42 = $2b
    .label __61 = $79
    .label __62 = $79
    .label __66 = $71
    .label __67 = $71
    .label __68 = $71
    .label __69 = $71
    .label __70 = $71
    .label __71 = $71
    .label __72 = $71
    .label __74 = $7d
    .label __76 = $7b
    .label __77 = $7b
    .label __84 = $73
    .label __85 = $73
    .label __86 = $73
    .label __87 = $73
    .label __88 = $73
    .label __89 = $73
    .label __90 = $73
    // struct tm tm
    ldy #SIZEOF_STRUCT_TM
    lda #0
  !:
    dey
    sta tm,y
    bne !-
    // time_t t
    lda #<0
    sta.z t
    sta.z t+1
    sta.z t+2
    sta.z t+3
    // t = 0
  // asctime/ctime on the epoch, a leap day, the 2038/2106 edges and the non-leap year 2100
    // ctime_r(&t, buf)
    jsr ctime_r
    // check_str("epoch", ctime_r(&t, buf), "Thu Jan  1 00:00:00 1970\n")
    lda #<name
    sta.z check_str.name
    lda #>name
    sta.z check_str.name+1
    lda #0
    sta.z failures
    lda #<expected
    sta.z check_str.expected
    lda #>expected
    sta.z check_str.expected+1
    lda #0
    sta.z tests
    jsr check_str
    // t = 1_000_000_000UL
    lda #<$3b9aca00
    sta.z t
    lda #>$3b9aca00
    sta.z t+1
    lda #<$3b9aca00>>$10
    sta.z t+2
    lda #>$3b9aca00>>$10
    sta.z t+3
    // ctime_r(&t, buf)
    jsr ctime_r
    // check_str("1e9", ctime_r(&t, buf), "Sun Sep  9 01:46:40 2001\n")
    lda #<name1
    sta.z check_str.name
    lda #>name1
    sta.z check_str.name+1
    lda #<expected1
    sta.z check_str.expected
    lda #>expected1
    sta.z check_str.expected+1
    jsr check_str
    // t = 1_700_000_000UL
    lda #<$6553f100
    sta.z t
    lda #>$6553f100
    sta.z t+1
    lda #<$6553f100>>$10
    sta.z t+2
    lda #>$6553f100>>$10
    sta.z t+3
    // ctime_r(&t, buf)
    jsr ctime_r
    // check_str("1.7e9", ctime_r(&t, buf), "Tue Nov 14 22:13:20 2023\n")
    lda #<name2
    sta.z check_str.name
    lda #>name2
    sta.z check_str.name+1
    lda #<expected2
    sta.z check_str.expected
    lda #>expected2
    sta.z check_str.expected+1
    jsr check_str
    // t = 951_782_400UL
    lda #<$38bb0c00
    sta.z t
    lda #>$38bb0c00
    sta.z t+1
    lda #<$38bb0c00>>$10
    sta.z t+2
    lda #>$38bb0c00>>$10
    sta.z t+3
    // ctime_r(&t, buf)
    jsr ctime_r
    // check_str("2000-02-29", ctime_r(&t, buf), "Tue Feb 29 00:00:00 2000\n")
    lda #<name3
    sta.z check_str.name
    lda #>name3
    sta.z check_str.name+1
    lda #<expected3
    sta.z check_str.expected
    lda #>expected3
    sta.z check_str.expected+1
    jsr check_str
    // t = 2_147_483_647UL
    lda #<$7fffffff
    sta.z t
    sta.z t+1
    sta.z t+2
    lda #>$7fffffff>>$10
    sta.z t+3
    // ctime_r(&t, buf)
    jsr ctime_r
    // check_str("2038", ctime_r(&t, buf), "Tue Jan 19 03:14:07 2038\n")
    lda #<name4
    sta.z check_str.name
    lda #>name4
    sta.z check_str.name+1
    lda #<expected4
    sta.z check_str.expected
    lda #>expected4
    sta.z check_str.expected+1
    jsr check_str
    // t = 4_107_542_400UL
    lda #<$f4d41f80
    sta.z t
    lda #>$f4d41f80
    sta.z t+1
    lda #<$f4d41f80>>$10
    sta.z t+2
    lda #>$f4d41f80>>$10
    sta.z t+3
    // ctime_r(&t, buf)
    jsr ctime_r
    // check_str("2100-03-01", ctime_r(&t, buf), "Mon Mar  1 00:00:00 2100\n")
    lda #<name5
    sta.z check_str.name
    lda #>name5
    sta.z check_str.name+1
    lda #<expected5
    sta.z check_str.expected
    lda #>expected5
    sta.z check_str.expected+1
    jsr check_str
    // t = 4_294_967_295UL
    lda #<$ffffffff
    sta.z t
    sta.z t+1
    sta.z t+2
    sta.z t+3
    // ctime_r(&t, buf)
    jsr ctime_r
    // check_str("max", ctime_r(&t, buf), "Sun Feb  7 06:28:15 2106\n")
    lda #<name6
    sta.z check_str.name
    lda #>name6
    sta.z check_str.name+1
    lda #<expected6
    sta.z check_str.expected
    lda #>expected6
    sta.z check_str.expected+1
    jsr check_str
    // t = 1_700_000_000UL
    // mktime
    lda #<$6553f100
    sta.z t
    lda #>$6553f100
    sta.z t+1
    lda #<$6553f100>>$10
    sta.z t+2
    lda #>$6553f100>>$10
    sta.z t+3
    // gmtime_r(&t, &tm)
    lda #<tm
    sta.z gmtime_r.result
    lda #>tm
    sta.z gmtime_r.result+1
    lda #<t
    sta.z gmtime_r.timer
    lda #>t
    sta.z gmtime_r.timer+1
    jsr gmtime_r
    // mktime(&tm)
    jsr mktime
    // mktime(&tm)
    // check_ulong("mktime", mktime(&tm), 1_700_000_000UL)
    lda #<name7
    sta.z check_ulong.name
    lda #>name7
    sta.z check_ulong.name+1
    lda #<$6553f100
    sta.z check_ulong.expected
    lda #>$6553f100
    sta.z check_ulong.expected+1
    lda #<$6553f100>>$10
    sta.z check_ulong.expected+2
    lda #>$6553f100>>$10
    sta.z check_ulong.expected+3
    jsr check_ulong
    // check_ulong("yday", tm.tm_yday, 317)
    lda.z tm+OFFSET_STRUCT_TM_TM_YDAY
    sta.z check_ulong.actual
    lda.z tm+OFFSET_STRUCT_TM_TM_YDAY+1
    sta.z check_ulong.actual+1
    lda #0
    sta.z check_ulong.actual+2
    sta.z check_ulong.actual+3
    lda #<name8
    sta.z check_ulong.name
    lda #>name8
    sta.z check_ulong.name+1
    lda #<$13d
    sta.z check_ulong.expected
    lda #>$13d
    sta.z check_ulong.expected+1
    lda #<$13d>>$10
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // check_ulong("wday", tm.tm_wday, TM_TUESDAY)
    lda.z tm+OFFSET_STRUCT_TM_TM_WDAY
    sta.z check_ulong.actual
    lda #0
    sta.z check_ulong.actual+1
    sta.z check_ulong.actual+2
    sta.z check_ulong.actual+3
    lda #<name9
    sta.z check_ulong.name
    lda #>name9
    sta.z check_ulong.name+1
    lda #TM_TUESDAY
    sta.z check_ulong.expected
    lda #0
    sta.z check_ulong.expected+1
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // tm.tm_mon = 12
    // Normalization: month 12 of 2023 is January 2024
    lda #$c
    sta.z tm+OFFSET_STRUCT_TM_TM_MON
    // tm.tm_mday = 1
    lda #1
    sta.z tm+OFFSET_STRUCT_TM_TM_MDAY
    // tm.tm_hour = 0
    lda #0
    sta.z tm+OFFSET_STRUCT_TM_TM_HOUR
    // tm.tm_min = 0
    sta.z tm+OFFSET_STRUCT_TM_TM_MIN
    // tm.tm_sec = 0
    sta.z tm
    // mktime(&tm)
    jsr mktime
    // mktime(&tm)
    // check_ulong("normalize", mktime(&tm), 1_704_067_200UL)
    lda #<name10
    sta.z check_ulong.name
    lda #>name10
    sta.z check_ulong.name+1
    lda #<$65920080
    sta.z check_ulong.expected
    lda #>$65920080
    sta.z check_ulong.expected+1
    lda #<$65920080>>$10
    sta.z check_ulong.expected+2
    lda #>$65920080>>$10
    sta.z check_ulong.expected+3
    jsr check_ulong
    // check_ulong("normalize year", tm.tm_year, 124)
    lda.z tm+OFFSET_STRUCT_TM_TM_YEAR
    sta.z check_ulong.actual
    lda.z tm+OFFSET_STRUCT_TM_TM_YEAR+1
    sta.z check_ulong.actual+1
    lda #0
    sta.z check_ulong.actual+2
    sta.z check_ulong.actual+3
    lda #<name11
    sta.z check_ulong.name
    lda #>name11
    sta.z check_ulong.name+1
    lda #$7c
    sta.z check_ulong.expected
    lda #0
    sta.z check_ulong.expected+1
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // check_ulong("normalize mon", tm.tm_mon, TM_JANUARY)
    lda.z tm+OFFSET_STRUCT_TM_TM_MON
    sta.z check_ulong.actual
    lda #0
    sta.z check_ulong.actual+1
    sta.z check_ulong.actual+2
    sta.z check_ulong.actual+3
    lda #<name12
    sta.z check_ulong.name
    lda #>name12
    sta.z check_ulong.name+1
    lda #TM_JANUARY
    sta.z check_ulong.expected
    lda #0
    sta.z check_ulong.expected+1
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // tm.tm_year = 69
    // Out of range
    lda #<$45
    sta.z tm+OFFSET_STRUCT_TM_TM_YEAR
    lda #>$45
    sta.z tm+OFFSET_STRUCT_TM_TM_YEAR+1
    // mktime(&tm)
    jsr mktime
    // mktime(&tm)
    // check_ulong("before 1970", mktime(&tm), TIME_T_INVALID)
    lda #<name13
    sta.z check_ulong.name
    lda #>name13
    sta.z check_ulong.name+1
    lda #<$ffffffff
    sta.z check_ulong.expected
    sta.z check_ulong.expected+1
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // difftime(100, 40)
    lda #$28
    sta.z difftime.beginning
    lda #0
    sta.z difftime.beginning+1
    sta.z difftime.beginning+2
    sta.z difftime.beginning+3
    lda #$64
    sta.z difftime.end
    lda #0
    sta.z difftime.end+1
    sta.z difftime.end+2
    sta.z difftime.end+3
    jsr difftime
    // difftime(100, 40)
    // check_ulong("difftime+", (time_t)difftime(100, 40), 60)
  // difftime
    lda #<name14
    sta.z check_ulong.name
    lda #>name14
    sta.z check_ulong.name+1
    lda #$3c
    sta.z check_ulong.expected
    lda #0
    sta.z check_ulong.expected+1
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // difftime(40, 100)
    lda #$64
    sta.z difftime.beginning
    lda #0
    sta.z difftime.beginning+1
    sta.z difftime.beginning+2
    sta.z difftime.beginning+3
    lda #$28
    sta.z difftime.end
    lda #0
    sta.z difftime.end+1
    sta.z difftime.end+2
    sta.z difftime.end+3
    jsr difftime
    // difftime(40, 100)
    // check_ulong("difftime-", (time_t)difftime(40, 100), (time_t)-60L)
    lda #<name15
    sta.z check_ulong.name
    lda #>name15
    sta.z check_ulong.name+1
    lda #<$ffffffff&-$3c
    sta.z check_ulong.expected
    lda #>$ffffffff&-$3c
    sta.z check_ulong.expected+1
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // t = 1_700_000_000UL
    // strftime
    lda #<$6553f100
    sta.z t
    lda #>$6553f100
    sta.z t+1
    lda #<$6553f100>>$10
    sta.z t+2
    lda #>$6553f100>>$10
    sta.z t+3
    // gmtime_r(&t, &tm)
    lda #<tm
    sta.z gmtime_r.result
    lda #>tm
    sta.z gmtime_r.result+1
    lda #<t
    sta.z gmtime_r.timer
    lda #>t
    sta.z gmtime_r.timer+1
    jsr gmtime_r
    // strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &tm)
    lda #<format
    sta.z strftime.format
    lda #>format
    sta.z strftime.format+1
    lda #<$28*SIZEOF_CHAR
    sta.z strftime.maxsize
    lda #>$28*SIZEOF_CHAR
    sta.z strftime.maxsize+1
    jsr strftime
    // check_str("strftime iso", buf, "2023-11-14 22:13:20")
    lda #<name16
    sta.z check_str.name
    lda #>name16
    sta.z check_str.name+1
    lda #<expected7
    sta.z check_str.expected
    lda #>expected7
    sta.z check_str.expected+1
    jsr check_str
    // strftime(buf, sizeof(buf), "%a %A %b %B %j", &tm)
    lda #<format1
    sta.z strftime.format
    lda #>format1
    sta.z strftime.format+1
    lda #<$28*SIZEOF_CHAR
    sta.z strftime.maxsize
    lda #>$28*SIZEOF_CHAR
    sta.z strftime.maxsize+1
    jsr strftime
    // check_str("strftime names", buf, "Tue Tuesday Nov November 318")
    lda #<name17
    sta.z check_str.name
    lda #>name17
    sta.z check_str.name+1
    lda #<expected8
    sta.z check_str.expected
    lda #>expected8
    sta.z check_str.expected+1
    jsr check_str
    // strftime(buf, sizeof(buf), "%I %p %e %y %C %u %w %%", &tm)
    lda #<format2
    sta.z strftime.format
    lda #>format2
    sta.z strftime.format+1
    lda #<$28*SIZEOF_CHAR
    sta.z strftime.maxsize
    lda #>$28*SIZEOF_CHAR
    sta.z strftime.maxsize+1
    jsr strftime
    // check_str("strftime misc", buf, "10 PM 14 23 20 2 2 %")
    lda #<name18
    sta.z check_str.name
    lda #>name18
    sta.z check_str.name+1
    lda #<expected9
    sta.z check_str.expected
    lda #>expected9
    sta.z check_str.expected+1
    jsr check_str
    // strftime(buf, sizeof(buf), "%F %T %R %D", &tm)
    lda #<format3
    sta.z strftime.format
    lda #>format3
    sta.z strftime.format+1
    lda #<$28*SIZEOF_CHAR
    sta.z strftime.maxsize
    lda #>$28*SIZEOF_CHAR
    sta.z strftime.maxsize+1
    jsr strftime
    // check_str("strftime composite", buf, "2023-11-14 22:13:20 22:13 11/14/23")
    lda #<name19
    sta.z check_str.name
    lda #>name19
    sta.z check_str.name+1
    lda #<expected10
    sta.z check_str.expected
    lda #>expected10
    sta.z check_str.expected+1
    jsr check_str
    // strftime(buf, 5, "%Y-%m-%d", &tm)
    lda #<format4
    sta.z strftime.format
    lda #>format4
    sta.z strftime.format+1
    lda #<5
    sta.z strftime.maxsize
    lda #>5
    sta.z strftime.maxsize+1
    jsr strftime
    // strftime(buf, 5, "%Y-%m-%d", &tm)
    // check_ulong("strftime overflow", strftime(buf, 5, "%Y-%m-%d", &tm), 0)
    lda.z strftime.return
    sta.z check_ulong.actual
    lda.z strftime.return+1
    sta.z check_ulong.actual+1
    lda #0
    sta.z check_ulong.actual+2
    sta.z check_ulong.actual+3
    lda #<name20
    sta.z check_ulong.name
    lda #>name20
    sta.z check_ulong.name+1
    lda #0
    sta.z check_ulong.expected
    sta.z check_ulong.expected+1
    sta.z check_ulong.expected+2
    sta.z check_ulong.expected+3
    jsr check_ulong
    // tod_set_frequency(50)
    // TOD clock: set and read back
    jsr tod_set_frequency
    // set_time(1_700_000_000UL)
    jsr set_time
    // time(nullptr)
    jsr time
    // t = time(nullptr)
    lda.z __42
    sta.z t
    lda.z __42+1
    sta.z t+1
    lda.z __42+2
    sta.z t+2
    lda.z __42+3
    sta.z t+3
    // tests++;
    inc.z tests
    // if (t < 1_700_000_000UL || t > 1_700_000_002UL)
    cmp #>$6553f100>>$10
    bcc __b2
    bne !+
    lda.z t+2
    cmp #<$6553f100>>$10
    bcc __b2
    bne !+
    lda.z t+1
    cmp #>$6553f100
    bcc __b2
    bne !+
    lda.z t
    cmp #<$6553f100
    bcc __b2
  !:
    lda.z t+3
    cmp #>$6553f103>>$10
    bcc __b1
    bne !+
    lda.z t+2
    cmp #<$6553f103>>$10
    bcc __b1
    bne !+
    lda.z t+1
    cmp #>$6553f103
    bcc __b1
    bne !+
    lda.z t
    cmp #<$6553f103
    bcc __b1
  !:
  __b2:
    // failures++;
    inc.z failures
  __b1:
    // cputs("tests: ")
    lda #<s
    sta.z cputs.s
    lda #>s
    sta.z cputs.s+1
    jsr cputs
    // tests / 10
    lda.z tests
    sta.z __61
    lda #0
    sta.z __61+1
    lda.z __61
    asl
    sta.z __66
    lda.z __61+1
    rol
    sta.z __66+1
    clc
    lda.z __67
    adc.z __61
    sta.z __67
    lda.z __67+1
    adc.z __61+1
    sta.z __67+1
    asl.z __68
    rol.z __68+1
    asl.z __68
    rol.z __68+1
    asl.z __68
    rol.z __68+1
    clc
    lda.z __69
    adc.z __61
    sta.z __69
    lda.z __69+1
    adc.z __61+1
    sta.z __69+1
    asl.z __70
    rol.z __70+1
    clc
    lda.z __71
    adc.z __61
    sta.z __71
    lda.z __71+1
    adc.z __61+1
    sta.z __71+1
    asl.z __72
    rol.z __72+1
    asl.z __72
    rol.z __72+1
    clc
    lda.z __62
    adc.z __72
    sta.z __62
    lda.z __62+1
    adc.z __72+1
    sta.z __62+1
    lsr
    lsr
    lsr
    // cputc('0' + tests / 10)
    tax
    axs #-['0']
    jsr cputc
    // tests % 10
    lda.z tests
    sta.z __76
    lda #0
    sta.z __76+1
    lda.z __76
    asl
    sta.z __84
    lda.z __76+1
    rol
    sta.z __84+1
    clc
    lda.z __85
    adc.z __76
    sta.z __85
    lda.z __85+1
    adc.z __76+1
    sta.z __85+1
    asl.z __86
    rol.z __86+1
    asl.z __86
    rol.z __86+1
    asl.z __86
    rol.z __86+1
    clc
    lda.z __87
    adc.z __76
    sta.z __87
    lda.z __87+1
    adc.z __76+1
    sta.z __87+1
    asl.z __88
    rol.z __88+1
    clc
    lda.z __89
    adc.z __76
    sta.z __89
    lda.z __89+1
    adc.z __76+1
    sta.z __89+1
    asl.z __90
    rol.z __90+1
    asl.z __90
    rol.z __90+1
    clc
    lda.z __77
    adc.z __90
    sta.z __77
    lda.z __77+1
    adc.z __90+1
    sta.z __77+1
    lsr
    lsr
    lsr
    sta.z __74
    asl
    asl
    clc
    adc.z __74
    asl
    eor #$ff
    sec
    adc.z tests
    // cputc('0' + tests % 10)
    tax
    axs #-['0']
    jsr cputc
    // cputs(" failures: ")
    lda #<s1
    sta.z cputs.s
    lda #>s1
    sta.z cputs.s+1
    jsr cputs
    // cputc('0' + failures)
    lax.z failures
    axs #-['0']
    jsr cputc
    // cputs("\r\n")
    lda #<s2
    sta.z cputs.s
    lda #>s2
    sta.z cputs.s+1
    jsr cputs
    // if (failures == 0)
    lda.z failures
    bne __b4
    // cputs("all ok\r\n")
    lda #<s3
    sta.z cputs.s
    lda #>s3
    sta.z cputs.s+1
    jsr cputs
  __b4:
    jmp __b4
  .segment Data
    buf: .fill $28, 0
    name: .text "epoch"
    .byte 0
    expected: .text @"Thu Jan  1 00:00:00 1970\n"
    .byte 0
    name1: .text "1e9"
    .byte 0
    expected1: .text @"Sun Sep  9 01:46:40 2001\n"
    .byte 0
    name2: .text "1.7e9"
    .byte 0
    expected2: .text @"Tue Nov 14 22:13:20 2023\n"
    .byte 0
    name3: .text "2000-02-29"
    .byte 0
    expected3: .text @"Tue Feb 29 00:00:00 2000\n"
    .byte 0
    name4: .text "2038"
    .byte 0
    expected4: .text @"Tue Jan 19 03:14:07 2038\n"
    .byte 0
    name5: .text "2100-03-01"
    .byte 0
    expected5: .text @"Mon Mar  1 00:00:00 2100\n"
    .byte 0
    name6: .text "max"
    .byte 0
    expected6: .text @"Sun Feb  7 06:28:15 2106\n"
    .byte 0
    name7: .text "mktime"
    .byte 0
    name8: .text "yday"
    .byte 0
    name9: .text "wday"
    .byte 0
    name10: .text "normalize"
    .byte 0
    name11: .text "normalize year"
    .byte 0
    name12: .text "normalize mon"
    .byte 0
    name13: .text "before 1970"
    .byte 0
    name14: .text "difftime+"
    .byte 0
    name15: .text "difftime-"
    .byte 0
    format: .text "%Y-%m-%d %H:%M:%S"
    .byte 0
    name16: .text "strftime iso"
    .byte 0
    expected7: .text "2023-11-14 22:13:20"
    .byte 0
    format1: .text "%a %A %b %B %j"
    .byte 0
    name17: .text "strftime names"
    .byte 0
    expected8: .text "Tue Tuesday Nov November 318"
    .byte 0
    format2: .text "%I %p %e %y %C %u %w %%"
    .byte 0
    name18: .text "strftime misc"
    .byte 0
    expected9: .text "10 PM 14 23 20 2 2 %"
    .byte 0
    format3: .text "%F %T %R %D"
    .byte 0
    name19: .text "strftime composite"
    .byte 0
    expected10: .text "2023-11-14 22:13:20 22:13 11/14/23"
    .byte 0
    format4: .text "%Y-%m-%d"
    .byte 0
    name20: .text "strftime overflow"
    .byte 0
    s: .text "tests: "
    .byte 0
    s1: .text " failures: "
    .byte 0
    s3: .text @"all ok\r\n"
    .byte 0
}
.segment Code
// Set the cursor to the specified position
// void gotoxy(char x, __register(X) char y)
gotoxy: {
    .const x = 0
    .label __5 = $6a
    .label __6 = $56
    .label line_offset = $56
    .label __8 = $56
    .label __9 = $68
    .label __10 = $56
    // if(y>CONIO_HEIGHT)
    cpx #$1a
    bcc __b2
    ldx #0
  __b2:
    // conio_cursor_x = x
    lda #x
    sta.z conio_cursor_x
    // conio_cursor_y = y
    stx.z conio_cursor_y
    // unsigned int line_offset = (unsigned int)y*CONIO_WIDTH
    txa
    sta.z __8
    lda #0
    sta.z __8+1
    lda.z __8
    asl
    sta.z __9
    lda.z __8+1
    rol
    sta.z __9+1
    asl.z __9
    rol.z __9+1
    clc
    lda.z __10
    adc.z __9
    sta.z __10
    lda.z __10+1
    adc.z __9+1
    sta.z __10+1
    asl.z line_offset
    rol.z line_offset+1
    asl.z line_offset
    rol.z line_offset+1
    asl.z line_offset
    rol.z line_offset+1
    // CONIO_SCREEN_TEXT + line_offset
    lda.z line_offset
    clc
    sta.z __5
    lda.z line_offset+1
    adc #>DEFAULT_SCREEN
    sta.z __5+1
    // conio_line_text = CONIO_SCREEN_TEXT + line_offset
    lda.z __5
    sta.z conio_line_text
    lda.z __5+1
    sta.z conio_line_text+1
    // CONIO_SCREEN_COLORS + line_offset
    clc
    lda.z __6+1
    adc #>COLORRAM
    sta.z __6+1
    // conio_line_color = CONIO_SCREEN_COLORS + line_offset
    lda.z __6
    sta.z conio_line_color
    lda.z __6+1
    sta.z conio_line_color+1
    // }
    rts
}
// Converts calendar time to a string.
// char * ctime_r(const unsigned long *timer, char *buf)
ctime_r: {
    .label t = $a4
    // struct tm t
    ldy #SIZEOF_STRUCT_TM
    lda #0
  !:
    dey
    sta t,y
    bne !-
    // gmtime_r(timer, &t)
    lda #<t
    sta.z gmtime_r.result
    lda #>t
    sta.z gmtime_r.result+1
    lda #<main.t
    sta.z gmtime_r.timer
    lda #>main.t
    sta.z gmtime_r.timer+1
    jsr gmtime_r
    // gmtime_r(timer, &t)
    // asctime_r(gmtime_r(timer, &t), buf)
    jmp asctime_r
    // }
}
// void check_str(__zp($1a) const char *name, const char *actual, __zp($10) const char *expected)
check_str: {
    .label __0 = $e
    .label expected = $10
    .label name = $1a
    // tests++;
    inc.z tests
    // strcmp(actual, expected)
    jsr strcmp
    sta.z __0
    stx.z __0+1
    // strcmp(actual, expected)
    // if (strcmp(actual, expected) != 0)
    ora.z __0+1
    beq __breturn
    // failures++;
    inc.z failures
    // cputs("FAIL ")
    lda #<s
    sta.z cputs.s
    lda #>s
    sta.z cputs.s+1
    jsr cputs
    // cputs(name)
    lda.z name
    sta.z cputs.s
    lda.z name+1
    sta.z cputs.s+1
    jsr cputs
    // cputs(": ")
    lda #<s1
    sta.z cputs.s
    lda #>s1
    sta.z cputs.s+1
    jsr cputs
    // cputs(actual)
    lda #<main.buf
    sta.z cputs.s
    lda #>main.buf
    sta.z cputs.s+1
    jsr cputs
    // cputs("\r\n")
    lda #<s2
    sta.z cputs.s
    lda #>s2
    sta.z cputs.s+1
    jmp cputs
  __breturn:
    // }
    rts
  .segment Data
    s1: .text ": "
    .byte 0
}
.segment Code
// Converts calendar time to broken-down UTC time into the caller's struct.
// __zp($1a) struct tm * gmtime_r(__zp($10) const unsigned long *timer, __zp($1a) struct tm *result)
gmtime_r: {
    .label __6 = 4
    .label __10 = 7
    .label __11 = $a
    .label __35 = $a
    .label t = $42
    .label days = 2
    // Find the year: jump to 2000 / 2100 and over blocks of 4 years, then step year by year
    .label year = $a
    .label yl = $22
    .label dim = $21
    .label return = $1a
    .label timer = $10
    .label result = $1a
    // time_t t = *timer
    ldy #0
    lda (timer),y
    sta.z t
    iny
    lda (timer),y
    sta.z t+1
    iny
    lda (timer),y
    sta.z t+2
    iny
    lda (timer),y
    sta.z t+3
    // uint16_t days = split_days(t)
    jsr split_days
    // uint16_t days = split_days(t)
    // split_hms(split_sod)
    lda.z split_sod
    sta.z split_hms.sod
    lda.z split_sod+1
    sta.z split_hms.sod+1
    lda.z split_sod+2
    sta.z split_hms.sod+2
    lda.z split_sod+3
    sta.z split_hms.sod+3
    jsr split_hms
    // result->tm_hour = split_h
    lda.z split_h
    ldy #OFFSET_STRUCT_TM_TM_HOUR
    sta (result),y
    // result->tm_min = split_m
    lda.z split_m
    ldy #OFFSET_STRUCT_TM_TM_MIN
    sta (result),y
    // result->tm_sec = split_s
    lda.z split_s
    ldy #0
    sta (result),y
    // div16u(days + 4, 7)
    lda #4
    clc
    adc.z days
    sta.z div16u.dividend
    tya
    adc.z days+1
    sta.z div16u.dividend+1
  // 1970-01-01 was a Thursday
    lda #<7
    sta.z div16u.divisor
    tya
    sta.z div16u.divisor+1
    jsr div16u
    // result->tm_wday = (uint8_t)rem16u
    ldy #OFFSET_STRUCT_TM_TM_WDAY
    lda.z divr16u.rem
    sta (result),y
    // if (days >= DAYS_1970_TO_2000)
    lda.z days+1
    cmp #>$2acd
    bcc __b2
    bne !+
    lda.z days
    cmp #<$2acd
    bcc __b2
  !:
    // days -= DAYS_1970_TO_2000
    lda.z days
    sec
    sbc #<$2acd
    sta.z days
    lda.z days+1
    sbc #>$2acd
    sta.z days+1
    // if (days >= DAYS_2000_TO_2100)
    cmp #>$8ead
    bcc !+
    beq !__b3+
    jmp __b3
  !__b3:
    lda.z days
    cmp #<$8ead
    bcc !__b3+
    jmp __b3
  !__b3:
  !:
    // div16u(days, DAYS_PER_4_YEARS)
    lda.z days
    sta.z div16u.dividend
    lda.z days+1
    sta.z div16u.dividend+1
    lda #<$5b5
    sta.z div16u.divisor
    lda #>$5b5
    sta.z div16u.divisor+1
    jsr div16u
    // div16u(days, DAYS_PER_4_YEARS)
    // uint8_t q = (uint8_t)div16u(days, DAYS_PER_4_YEARS)
    ldx.z __10
    // days = rem16u
    lda.z divr16u.rem
    sta.z days
    lda.z divr16u.rem+1
    sta.z days+1
    // (uint16_t)q * 4
    txa
    sta.z __35
    lda #0
    sta.z __35+1
    asl.z __11
    rol.z __11+1
    asl.z __11
    rol.z __11+1
    // year += (uint16_t)q * 4
    clc
    lda.z year
    adc #<$7d0
    sta.z year
    lda.z year+1
    adc #>$7d0
    sta.z year+1
    jmp __b5
  __b2:
    lda #<$7b2
    sta.z year
    lda #>$7b2
    sta.z year+1
  __b5:
    // is_leap_year(year)
    jsr is_leap_year
    // is_leap_year(year)
    // is_leap_year(year) ? (uint16_t)366 : (uint16_t)365
    bcs __b6
    lda #<$16d
    sta.z yl
    lda #>$16d
    sta.z yl+1
    jmp __b7
  __b6:
    // is_leap_year(year) ? (uint16_t)366 : (uint16_t)365
    lda #<$16e
    sta.z yl
    lda #>$16e
    sta.z yl+1
  __b7:
    // if (uint16_t yl = is_leap_year(year) ? (uint16_t)366 : (uint16_t)365; days >= yl)
    lda.z days+1
    cmp.z yl+1
    bne !+
    lda.z days
    cmp.z yl
  !:
    bcs __b8
    // year - 1900
    sec
    lda.z year
    sbc #<$76c
    sta.z __6
    lda.z year+1
    sbc #>$76c
    sta.z __6+1
    // result->tm_year = year - 1900
    ldy #OFFSET_STRUCT_TM_TM_YEAR
    lda.z __6
    sta (result),y
    iny
    lda.z __6+1
    sta (result),y
    // result->tm_yday = days
    ldy #OFFSET_STRUCT_TM_TM_YDAY
    lda.z days
    sta (result),y
    iny
    lda.z days+1
    sta (result),y
    // bool leap = is_leap_year(year)
  // Find the month
    jsr is_leap_year
    // bool leap = is_leap_year(year)
    lda #0
    rol
    tax
    ldy #0
  __b11:
    // uint8_t dim = MONTH_DAYS[mon]
    lda MONTH_DAYS,y
    sta.z dim
    // if (mon == TM_FEBRUARY && leap)
    cpy #TM_FEBRUARY
    bne __b12
    cpx #0
    beq __b12
  __b15:
    // dim++;
    inc.z dim
  __b12:
    // if (days < dim)
    lda.z days+1
    bne __b13
    lda.z days
    cmp.z dim
    bcs __b13
  !:
    // result->tm_mon = mon
    tya
    ldy #OFFSET_STRUCT_TM_TM_MON
    sta (result),y
    // (uint8_t)days + 1
    ldx.z days
    inx
    txa
    // result->tm_mday = (uint8_t)days + 1
    ldy #OFFSET_STRUCT_TM_TM_MDAY
    sta (result),y
    // result->tm_isdst = 0
    lda #0
    ldy #OFFSET_STRUCT_TM_TM_ISDST
    sta (result),y
    // }
    rts
  __b13:
    // days -= dim
    sec
    lda.z days
    sbc.z dim
    sta.z days
    bcs !+
    dec.z days+1
  !:
    // mon++;
    iny
    jmp __b11
  __b8:
    // days -= yl
    lda.z days
    sec
    sbc.z yl
    sta.z days
    lda.z days+1
    sbc.z yl+1
    sta.z days+1
    // year++;
    inc.z year
    bne !+
    inc.z year+1
  !:
    jmp __b5
  __b3:
    // days -= DAYS_2000_TO_2100
    lda.z days
    sec
    sbc #<$8ead
    sta.z days
    lda.z days+1
    sbc #>$8ead
    sta.z days+1
    lda #<$834
    sta.z year
    lda #>$834
    sta.z year+1
    jmp __b5
}
// Converts broken-down time (interpreted as UTC) to calendar time and normalizes the fields.
// __zp($49) unsigned long mktime(struct tm *tp)
mktime: {
    .label __10 = $2f
    .label __17 = $5e
    .label __20 = $4e
    .label t = $82
    .label __34 = $6c
    .label year = $50
    .label mon = 6
    .label days = $52
    .label return = $49
    .label base = $5e
    .label secs = $2b
    .label __44 = $4e
    .label __45 = $47
    .label __46 = $47
    .label __47 = $47
    .label __48 = $47
    .label __49 = $47
    .label __50 = $4e
    .label __60 = 9
    .label __63 = $39
    .label __70 = $39
    .label __71 = $39
    .label __72 = $39
    .label __73 = $39
    .label __74 = $39
    .label __75 = $39
    .label __76 = $39
    // uint16_t year = tp->tm_year + 1900
    lda.z main.tm+OFFSET_STRUCT_TM_TM_YEAR
    clc
    adc #<$76c
    sta.z year
    lda.z main.tm+OFFSET_STRUCT_TM_TM_YEAR+1
    adc #>$76c
    sta.z year+1
    // uint8_t mon = tp->tm_mon
    lax.z main.tm+OFFSET_STRUCT_TM_TM_MON
    // mon / 12
    asl
    asl
    sta.z __70
    lda #0
    rol
    sta.z __70+1
    txa
    clc
    adc.z __71
    sta.z __71
    bcc !+
    inc.z __71+1
  !:
    asl.z __72
    rol.z __72+1
    asl.z __72
    rol.z __72+1
    txa
    clc
    adc.z __73
    sta.z __73
    bcc !+
    inc.z __73+1
  !:
    asl.z __74
    rol.z __74+1
    asl.z __74
    rol.z __74+1
    txa
    clc
    adc.z __75
    sta.z __75
    bcc !+
    inc.z __75+1
  !:
    asl.z __76
    rol.z __76+1
    txa
    clc
    adc.z __63
    sta.z __63
    bcc !+
    inc.z __63+1
  !:
    lda.z __63+1
    lsr
    lsr
    lsr
    sta.z __60
    // year += mon / 12
    // Normalize months beyond December
    clc
    adc.z year
    sta.z year
    bcc !+
    inc.z year+1
  !:
    // mon = mon % 12
    lda.z __60
    asl
    clc
    adc.z __60
    asl
    asl
    sta.z $ff
    txa
    sec
    sbc.z $ff
    sta.z mon
    // if (year < 1970 || year > 2106)
    lda.z year+1
    cmp #>$7b2
    bcs !__b6+
    jmp __b6
  !__b6:
    bne !+
    lda.z year
    cmp #<$7b2
    bcs !__b6+
    jmp __b6
  !__b6:
  !:
    lda.z year+1
    cmp #>$83b
    bcc !+
    beq !__b6+
    jmp __b6
  !__b6:
    lda.z year
    cmp #<$83b
    bcc !__b6+
    jmp __b6
  !__b6:
  !:
    // uint32_t days = days_before_year(year)
    lda.z year
    sta.z days_before_year.year
    lda.z year+1
    sta.z days_before_year.year+1
    jsr days_before_year
    lda.z days_before_year.return
    sta.z days
    lda.z days_before_year.return+1
    sta.z days+1
    lda #0
    sta.z days+2
    sta.z days+3
    // DAYS_BEFORE_MONTH[mon]
    ldy.z mon
    ldx DAYS_BEFORE_MONTH,y
    lda DAYS_BEFORE_MONTH+$c,y
    sta.z __10+1
    stx.z __10
    // days += DAYS_BEFORE_MONTH[mon]
    lda.z days
    clc
    adc.z __10
    sta.z days
    lda.z days+1
    adc.z __10+1
    sta.z days+1
    lda.z days+2
    adc #0
    sta.z days+2
    lda.z days+3
    adc #0
    sta.z days+3
    // is_leap_year(year)
    lda.z year
    sta.z is_leap_year.year
    lda.z year+1
    sta.z is_leap_year.year+1
    jsr is_leap_year
    // is_leap_year(year)
    lda #0
    rol
    tax
    // if (mon >= TM_MARCH && is_leap_year(year))
    lda.z mon
    cmp #TM_MARCH
    bcc __b2
    cpx #0
    beq __b2
  __b5:
    // days++;
    inc.z days
    bne !+
    inc.z days+1
    bne !+
    inc.z days+2
    bne !+
    inc.z days+3
  !:
  __b2:
    // days += (uint32_t)tp->tm_mday
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MDAY
    sta.z __34
    lda #0
    sta.z __34+1
    sta.z __34+2
    sta.z __34+3
    clc
    lda.z days
    adc.z __34
    sta.z days
    lda.z days+1
    adc.z __34+1
    sta.z days+1
    lda.z days+2
    adc.z __34+2
    sta.z days+2
    lda.z days+3
    adc.z __34+3
    sta.z days+3
    // days--;
    lda.z days
    sec
    sbc #1
    sta.z days
    lda.z days+1
    sbc #0
    sta.z days+1
    lda.z days+2
    sbc #0
    sta.z days+2
    lda.z days+3
    sbc #0
    sta.z days+3
    // if (days > DAYS_MAX)
    cmp #>$c22f>>$10
    bcc __b3
    bne !+
    lda.z days+2
    cmp #<$c22f>>$10
    bcc __b3
    bne !+
    lda.z days+1
    cmp #>$c22f
    bcc __b3
    bne !+
    lda.z days
    cmp #<$c22f
    bcc __b3
  !:
  __b6:
    lda #<$ffffffff
    sta.z return
    sta.z return+1
    sta.z return+2
    sta.z return+3
    // }
    rts
  __b3:
    // mul16u((uint16_t)days, 675)
    lda.z days
    sta.z mul16u.a
    lda.z days+1
    sta.z mul16u.a+1
    lda #<$2a3
    sta.z mul16u.b
    lda #>$2a3
    sta.z mul16u.b+1
    jsr mul16u
    // mul16u((uint16_t)days, 675)
    lda.z mul16u.return
    sta.z __17
    lda.z mul16u.return+1
    sta.z __17+1
    lda.z mul16u.return+2
    sta.z __17+2
    lda.z mul16u.return+3
    sta.z __17+3
    // time_t base = mul16u((uint16_t)days, 675) << 7
    // days * 86400 = days * 675 * 128 (explicit 16x16->32 bit multiplication, the product exceeds 16 bits)
    lda.z base+2
    lsr
    sta.z base+3
    lda.z base+1
    ror
    sta.z base+2
    lda.z base
    ror
    sta.z base+1
    lda #0
    ror
    sta.z base
    // time_t secs = mul16u(tp->tm_hour, SECS_PER_HOUR)
    lda.z main.tm+OFFSET_STRUCT_TM_TM_HOUR
    sta.z mul16u.a
    lda #0
    sta.z mul16u.a+1
    lda #<$e10
    sta.z mul16u.b
    lda #>$e10
    sta.z mul16u.b+1
    jsr mul16u
    // time_t secs = mul16u(tp->tm_hour, SECS_PER_HOUR)
    // (uint16_t)tp->tm_min * SECS_PER_MINUTE
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MIN
    sta.z __44
    lda #0
    sta.z __44+1
    lda.z __44
    asl
    sta.z __45
    lda.z __44+1
    rol
    sta.z __45+1
    clc
    lda.z __46
    adc.z __44
    sta.z __46
    lda.z __46+1
    adc.z __44+1
    sta.z __46+1
    asl.z __47
    rol.z __47+1
    clc
    lda.z __48
    adc.z __44
    sta.z __48
    lda.z __48+1
    adc.z __44+1
    sta.z __48+1
    asl.z __49
    rol.z __49+1
    clc
    lda.z __50
    adc.z __49
    sta.z __50
    lda.z __50+1
    adc.z __49+1
    sta.z __50+1
    asl.z __20
    rol.z __20+1
    asl.z __20
    rol.z __20+1
    // secs += (uint16_t)tp->tm_min * SECS_PER_MINUTE
    lda.z secs
    clc
    adc.z __20
    sta.z secs
    lda.z secs+1
    adc.z __20+1
    sta.z secs+1
    lda.z secs+2
    adc #0
    sta.z secs+2
    lda.z secs+3
    adc #0
    sta.z secs+3
    // secs += tp->tm_sec
    lda.z main.tm
    clc
    adc.z secs
    sta.z secs
    lda.z secs+1
    adc #0
    sta.z secs+1
    lda.z secs+2
    adc #0
    sta.z secs+2
    lda.z secs+3
    adc #0
    sta.z secs+3
    // time_t t = base + secs
    lda.z base
    clc
    adc.z secs
    sta.z t
    lda.z base+1
    adc.z secs+1
    sta.z t+1
    lda.z base+2
    adc.z secs+2
    sta.z t+2
    lda.z base+3
    adc.z secs+3
    sta.z t+3
    // if (t < base)
    cmp.z base+3
    bcc !+
    bne __b4
    lda.z t+2
    cmp.z base+2
    bcc !+
    bne __b4
    lda.z t+1
    cmp.z base+1
    bcc !+
    bne __b4
    lda.z t
    cmp.z base
    bcs __b4
  !:
    jmp __b6
  __b4:
    // gmtime_r(&t, tp)
  // Normalize all fields and set tm_wday and tm_yday
    lda #<main.tm
    sta.z gmtime_r.result
    lda #>main.tm
    sta.z gmtime_r.result+1
    lda #<t
    sta.z gmtime_r.timer
    lda #>t
    sta.z gmtime_r.timer+1
    jsr gmtime_r
    // return t;
    lda.z t
    sta.z return
    lda.z t+1
    sta.z return+1
    lda.z t+2
    sta.z return+2
    lda.z t+3
    sta.z return+3
    rts
}
// void check_ulong(__zp($a) const char *name, __zp($49) unsigned long actual, __zp($52) unsigned long expected)
check_ulong: {
    .label actual = $49
    .label expected = $52
    .label name = $a
    // tests++;
    inc.z tests
    // if (actual != expected)
    lda.z actual
    cmp.z expected
    bne !+
    lda.z actual+1
    cmp.z expected+1
    bne !+
    lda.z actual+2
    cmp.z expected+2
    bne !+
    lda.z actual+3
    cmp.z expected+3
    beq __breturn
  !:
    // failures++;
    inc.z failures
    // cputs("FAIL ")
    lda #<s
    sta.z cputs.s
    lda #>s
    sta.z cputs.s+1
    jsr cputs
    // cputs(name)
    lda.z name
    sta.z cputs.s
    lda.z name+1
    sta.z cputs.s+1
    jsr cputs
    // cputs("\r\n")
    lda #<s2
    sta.z cputs.s
    lda #>s2
    sta.z cputs.s+1
    jmp cputs
  __breturn:
    // }
    rts
}
// Returns the difference end - beginning in seconds.
// __zp($49) long difftime(__zp($49) unsigned long end, __zp($42) unsigned long beginning)
difftime: {
    .label __2 = $49
    .label __3 = $49
    .label return = $49
    .label end = $49
    .label beginning = $42
    // if (end >= beginning)
    lda.z end+3
    cmp.z beginning+3
    bcc __b1
    bne !+
    lda.z end+2
    cmp.z beginning+2
    bcc __b1
    bne !+
    lda.z end+1
    cmp.z beginning+1
    bcc __b1
    bne !+
    lda.z end
    cmp.z beginning
    bcc __b1
  !:
    // end - beginning
    lda.z __2
    sec
    sbc.z beginning
    sta.z __2
    lda.z __2+1
    sbc.z beginning+1
    sta.z __2+1
    lda.z __2+2
    sbc.z beginning+2
    sta.z __2+2
    lda.z __2+3
    sbc.z beginning+3
    sta.z __2+3
    // return (int32_t)(end - beginning);
    // }
    rts
  __b1:
    // beginning - end
    sec
    lda.z beginning
    sbc.z __3
    sta.z __3
    lda.z beginning+1
    sbc.z __3+1
    sta.z __3+1
    lda.z beginning+2
    sbc.z __3+2
    sta.z __3+2
    lda.z beginning+3
    sbc.z __3+3
    sta.z __3+3
    // return -(int32_t)(beginning - end);
    sec
    lda.z return
    eor #$ff
    adc #0
    sta.z return
    lda.z return+1
    eor #$ff
    adc #0
    sta.z return+1
    lda.z return+2
    eor #$ff
    adc #0
    sta.z return+2
    lda.z return+3
    eor #$ff
    adc #0
    sta.z return+3
    rts
}
// Formats broken-down time according to format.
// __zp($22) unsigned int strftime(char *s, __zp($22) unsigned int maxsize, __zp(2) const char *format, const struct tm *tp)
strftime: {
    .label __10 = $22
    .label wd = $21
    .label mn = $46
    .label return = $22
    .label year = $50
    .label format = 2
    .label f = $40
    .label maxsize = $22
    .label __110 = $3b
    .label __112 = $39
    .label __113 = $39
    .label __120 = $1a
    .label __121 = $1a
    .label __122 = $1a
    .label __123 = $1a
    .label __124 = $1a
    .label __125 = $1a
    .label __126 = $1a
    // if (maxsize == 0)
    lda.z maxsize
    ora.z maxsize+1
    bne __b1
  __b36:
    lda #<0
    sta.z return
    sta.z return+1
    // }
    rts
  __b1:
    // sf.ptr = s
    lda #<main.buf
    sta sf
    lda #>main.buf
    sta sf+1
    // maxsize - 1
    lda.z __10
    sec
    sbc #1
    sta.z __10
    lda.z __10+1
    sbc #0
    sta.z __10+1
    // sf.left = maxsize - 1
    lda.z __10
    sta sf+2
    lda.z __10+1
    sta sf+2+1
    // sf.ok = true
    lda #1
    sta sf+4
    // uint8_t wd = tp->tm_wday
    // Range-checked indices for the name tables
    lda.z main.tm+OFFSET_STRUCT_TM_TM_WDAY
    sta.z wd
    // uint8_t mn = tp->tm_mon
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MON
    sta.z mn
    // if (wd >= countof(WDAY_NAMES))
    lda.z wd
    cmp #7
    bcc __b2
    lda #0
    sta.z wd
  __b2:
    // if (mn >= countof(MON_NAMES))
    lda.z mn
    cmp #$c
    bcc __b3
    lda #0
    sta.z mn
  __b3:
    // uint16_t year = tp->tm_year + 1900
    lda.z main.tm+OFFSET_STRUCT_TM_TM_YEAR
    clc
    adc #<$76c
    sta.z year
    lda.z main.tm+OFFSET_STRUCT_TM_TM_YEAR+1
    adc #>$76c
    sta.z year+1
  __b5:
    // while (*format != 0 && sf.ok)
    ldy #0
    lda (format),y
    beq __b9
    lda sf+4
    bne __b6
  __b9:
    // if (!sf.ok)
    lda sf+4
    bne __b4
    // s[0] = 0
    lda #0
    sta main.buf
    jmp __b36
  __b4:
    // *sf.ptr = 0
    lda #0
    ldy sf
    sty.z $fe
    ldy sf+1
    sty.z $ff
    tay
    sta ($fe),y
    // return maxsize - 1 - sf.left;
    lda.z return
    sec
    sbc sf+2
    sta.z return
    lda.z return+1
    sbc sf+2+1
    sta.z return+1
    rts
  __b6:
    // char c = *format++
    ldy #0
    lax (format),y
    inc.z format
    bne !+
    inc.z format+1
  !:
    // if (c != '%')
    cpx #'%'
    beq __b7
    // sf_char(c)
    jsr sf_char
    jmp __b5
  __b7:
    // char f = *format
    ldy #0
    lda (format),y
    sta.z f
    // if (f == 0)
    bne __b8
    // sf_char('%')
    ldx #'%'
    jsr sf_char
    jmp __b9
  __b8:
    // format++;
    inc.z format
    bne !+
    inc.z format+1
  !:
    // case 'a': sf_abbr(WDAY_ABBR, wd); break;
    lda #'a'
    cmp.z f
    bne !__b10+
    jmp __b10
  !__b10:
    // case 'A': sf_str(WDAY_NAMES[wd]); break;
    lda #'A'
    cmp.z f
    bne !__b11+
    jmp __b11
  !__b11:
    // case 'b':
    lda #'b'
    cmp.z f
    bne !__b12+
    jmp __b12
  !__b12:
    // case 'h': sf_abbr(MON_ABBR, mn); break;
    lda #'h'
    cmp.z f
    bne !__b12+
    jmp __b12
  !__b12:
    // case 'B': sf_str(MON_NAMES[mn]); break;
    lda #'B'
    cmp.z f
    bne !__b13+
    jmp __b13
  !__b13:
    // case 'c':
    //                 sf_abbr(WDAY_ABBR, wd);
    //                 sf_char(' ');
    //                 sf_abbr(MON_ABBR, mn);
    //                 sf_char(' ');
    //                 sf_num(tp->tm_mday, 2, ' ');
    //                 sf_char(' ');
    //                 sf_time(tp, true);
    //                 sf_char(' ');
    //                 sf_num(year, 1, '0');
    //                 break;
    lda #'c'
    cmp.z f
    bne !__b14+
    jmp __b14
  !__b14:
    // case 'C': sf_num(div16u(year, 100), 2, '0'); break;
    lda #'C'
    cmp.z f
    bne !__b15+
    jmp __b15
  !__b15:
    // case 'd': sf_num(tp->tm_mday, 2, '0'); break;
    lda #'d'
    cmp.z f
    bne !__b16+
    jmp __b16
  !__b16:
    // case 'D':
    lda #'D'
    cmp.z f
    bne !__b17+
    jmp __b17
  !__b17:
    // case 'x': sf_date_us(tp); break;
    lda #'x'
    cmp.z f
    bne !__b17+
    jmp __b17
  !__b17:
    // case 'e': sf_num(tp->tm_mday, 2, ' '); break;
    lda #'e'
    cmp.z f
    bne !__b18+
    jmp __b18
  !__b18:
    // case 'F':
    //                 sf_num(year, 4, '0');
    //                 sf_char('-');
    //                 sf_num(tp->tm_mon + 1, 2, '0');
    //                 sf_char('-');
    //                 sf_num(tp->tm_mday, 2, '0');
    //                 break;
    lda #'F'
    cmp.z f
    bne !__b19+
    jmp __b19
  !__b19:
    // case 'H': sf_num(tp->tm_hour, 2, '0'); break;
    lda #'H'
    cmp.z f
    bne !__b20+
    jmp __b20
  !__b20:
    // case 'I':
    //                 if (uint8_t h12 = tp->tm_hour % 12; h12 == 0) sf_num(12, 2, '0');
    //                 else sf_num(h12, 2, '0');
    //                 break;
    lda #'I'
    cmp.z f
    bne !__b21+
    jmp __b21
  !__b21:
    // case 'j': sf_num(tp->tm_yday + 1, 3, '0'); break;
    lda #'j'
    cmp.z f
    bne !__b22+
    jmp __b22
  !__b22:
    // case 'm': sf_num(tp->tm_mon + 1, 2, '0'); break;
    lda #'m'
    cmp.z f
    bne !__b23+
    jmp __b23
  !__b23:
    // case 'M': sf_num(tp->tm_min, 2, '0'); break;
    lda #'M'
    cmp.z f
    bne !__b24+
    jmp __b24
  !__b24:
    // case 'n': sf_char('\n'); break;
    lda #'n'
    cmp.z f
    bne !__b25+
    jmp __b25
  !__b25:
    // case 'p': sf_str(tp->tm_hour >= 12 ? "PM" : "AM"); break;
    lda #'p'
    cmp.z f
    bne !__b26+
    jmp __b26
  !__b26:
    // case 'R': sf_time(tp, false); break;
    lda #'R'
    cmp.z f
    bne !__b27+
    jmp __b27
  !__b27:
    // case 'S': sf_num(tp->tm_sec, 2, '0'); break;
    lda #'S'
    cmp.z f
    bne !__b28+
    jmp __b28
  !__b28:
    // case 't': sf_char((char)9); break;
    lda #'t'
    cmp.z f
    bne !__b29+
    jmp __b29
  !__b29:
    // case 'T':
    lda #'T'
    cmp.z f
    bne !__b30+
    jmp __b30
  !__b30:
    // case 'X': sf_time(tp, true); break;
    lda #'X'
    cmp.z f
    bne !__b30+
    jmp __b30
  !__b30:
    // case 'u': sf_num(wd == 0 ? 7 : wd, 1, '0'); break;
    lda #'u'
    cmp.z f
    beq __b31
    // case 'w': sf_num(wd, 1, '0'); break;
    lda #'w'
    cmp.z f
    beq __b32
    // case 'y': sf_num(year_mod100(year), 2, '0'); break;
    lda #'y'
    cmp.z f
    beq __b33
    // case 'Y': sf_num(year, 1, '0'); break;
    lda #'Y'
    cmp.z f
    beq __b34
    // case '%': sf_char('%'); break;
    lda #'%'
    cmp.z f
    beq __b35
    // sf_char('%')
    tax
    jsr sf_char
    // sf_char(f)
    ldx.z f
    jsr sf_char
    jmp __b5
  __b35:
    // sf_char('%')
    ldx #'%'
    jsr sf_char
    jmp __b5
  __b34:
    // sf_num(year, 1, '0')
    lda.z year
    sta.z sf_num.v
    lda.z year+1
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #1
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b33:
    // year_mod100(year)
    lda.z year
    sta.z year_mod100.year
    lda.z year+1
    sta.z year_mod100.year+1
    jsr year_mod100
    // year_mod100(year)
    // sf_num(year_mod100(year), 2, '0')
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b32:
    // sf_num(wd, 1, '0')
    lda.z wd
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #1
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b31:
    // wd == 0 ? 7 : wd
    lda.z wd
    beq __b40
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    jmp __b41
  __b40:
    lda #<7
    sta.z sf_num.v
    lda #>7
    sta.z sf_num.v+1
  __b41:
    // sf_num(wd == 0 ? 7 : wd, 1, '0')
    lda #'0'
    sta.z sf_num.pad
    lda #1
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b30:
    // sf_time(tp, true)
    lda #1
    sta.z sf_time.withSeconds
    jsr sf_time
    jmp __b5
  __b29:
    // sf_char((char)9)
    ldx #9
    jsr sf_char
    jmp __b5
  __b28:
    // sf_num(tp->tm_sec, 2, '0')
    lda.z main.tm
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b27:
    // sf_time(tp, false)
    lda #0
    sta.z sf_time.withSeconds
    jsr sf_time
    jmp __b5
  __b26:
    // tp->tm_hour >= 12 ? "PM" : "AM"
    lda.z main.tm+OFFSET_STRUCT_TM_TM_HOUR
    cmp #$c
    bcs __b38
    lda #<__56
    sta.z sf_str.s
    lda #>__56
    sta.z sf_str.s+1
    jmp __b39
  __b38:
    // tp->tm_hour >= 12 ? "PM" : "AM"
    lda #<__57
    sta.z sf_str.s
    lda #>__57
    sta.z sf_str.s+1
  __b39:
    // sf_str(tp->tm_hour >= 12 ? "PM" : "AM")
    jsr sf_str
    jmp __b5
  __b25:
    // sf_char('\n')
    ldx #'\n'
    jsr sf_char
    jmp __b5
  __b24:
    // sf_num(tp->tm_min, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MIN
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b23:
    // sf_num(tp->tm_mon + 1, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MON
    clc
    adc #1
    sta.z sf_num.v
    lda #0
    adc #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b22:
    // sf_num(tp->tm_yday + 1, 3, '0')
    clc
    lda.z main.tm+OFFSET_STRUCT_TM_TM_YDAY
    adc #1
    sta.z sf_num.v
    lda.z main.tm+OFFSET_STRUCT_TM_TM_YDAY+1
    adc #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #3
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b21:
    // uint8_t h12 = tp->tm_hour % 12
    lda.z main.tm+OFFSET_STRUCT_TM_TM_HOUR
    sta.z __112
    lda #0
    sta.z __112+1
    lda.z __112
    asl
    sta.z __120
    lda.z __112+1
    rol
    sta.z __120+1
    asl.z __120
    rol.z __120+1
    clc
    lda.z __121
    adc.z __112
    sta.z __121
    lda.z __121+1
    adc.z __112+1
    sta.z __121+1
    asl.z __122
    rol.z __122+1
    asl.z __122
    rol.z __122+1
    clc
    lda.z __123
    adc.z __112
    sta.z __123
    lda.z __123+1
    adc.z __112+1
    sta.z __123+1
    asl.z __124
    rol.z __124+1
    asl.z __124
    rol.z __124+1
    clc
    lda.z __125
    adc.z __112
    sta.z __125
    lda.z __125+1
    adc.z __112+1
    sta.z __125+1
    asl.z __126
    rol.z __126+1
    clc
    lda.z __113
    adc.z __126
    sta.z __113
    lda.z __113+1
    adc.z __126+1
    sta.z __113+1
    lsr
    lsr
    lsr
    sta.z __110
    asl
    clc
    adc.z __110
    asl
    asl
    eor #$ff
    sec
    adc.z main.tm+OFFSET_STRUCT_TM_TM_HOUR
    // if (uint8_t h12 = tp->tm_hour % 12; h12 == 0)
    beq __b37
    // sf_num(h12, 2, '0')
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b37:
    // sf_num(12, 2, '0')
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    lda #<$c
    sta.z sf_num.v
    lda #>$c
    sta.z sf_num.v+1
    jsr sf_num
    jmp __b5
  __b20:
    // sf_num(tp->tm_hour, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_HOUR
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b19:
    // sf_num(year, 4, '0')
    lda.z year
    sta.z sf_num.v
    lda.z year+1
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #4
    sta.z sf_num.width
    jsr sf_num
    // sf_char('-')
    ldx #'-'
    jsr sf_char
    // sf_num(tp->tm_mon + 1, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MON
    clc
    adc #1
    sta.z sf_num.v
    lda #0
    adc #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    // sf_char('-')
    ldx #'-'
    jsr sf_char
    // sf_num(tp->tm_mday, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MDAY
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b18:
    // sf_num(tp->tm_mday, 2, ' ')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MDAY
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #' '
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b17:
    // sf_date_us(tp)
    jsr sf_date_us
    jmp __b5
  __b16:
    // sf_num(tp->tm_mday, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MDAY
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b15:
    // div16u(year, 100)
    lda.z year
    sta.z div16u.dividend
    lda.z year+1
    sta.z div16u.dividend+1
    lda #<$64
    sta.z div16u.divisor
    lda #>$64
    sta.z div16u.divisor+1
    jsr div16u
    // div16u(year, 100)
    // sf_num(div16u(year, 100), 2, '0')
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b14:
    // sf_abbr(WDAY_ABBR, wd)
    ldx.z wd
    lda #<WDAY_ABBR
    sta.z sf_abbr.table
    lda #>WDAY_ABBR
    sta.z sf_abbr.table+1
    jsr sf_abbr
    // sf_char(' ')
    ldx #' '
    jsr sf_char
    // sf_abbr(MON_ABBR, mn)
    ldx.z mn
    lda #<MON_ABBR
    sta.z sf_abbr.table
    lda #>MON_ABBR
    sta.z sf_abbr.table+1
    jsr sf_abbr
    // sf_char(' ')
    ldx #' '
    jsr sf_char
    // sf_num(tp->tm_mday, 2, ' ')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MDAY
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #' '
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    // sf_char(' ')
    ldx #' '
    jsr sf_char
    // sf_time(tp, true)
    lda #1
    sta.z sf_time.withSeconds
    jsr sf_time
    // sf_char(' ')
    ldx #' '
    jsr sf_char
    // sf_num(year, 1, '0')
    lda.z year
    sta.z sf_num.v
    lda.z year+1
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #1
    sta.z sf_num.width
    jsr sf_num
    jmp __b5
  __b13:
    // sf_str(MON_NAMES[mn])
    lda.z mn
    asl
    tay
    lda MON_NAMES,y
    sta.z sf_str.s
    lda MON_NAMES+1,y
    sta.z sf_str.s+1
    jsr sf_str
    jmp __b5
  __b12:
    // sf_abbr(MON_ABBR, mn)
    ldx.z mn
    lda #<MON_ABBR
    sta.z sf_abbr.table
    lda #>MON_ABBR
    sta.z sf_abbr.table+1
    jsr sf_abbr
    jmp __b5
  __b11:
    // sf_str(WDAY_NAMES[wd])
    lda.z wd
    asl
    tay
    lda WDAY_NAMES,y
    sta.z sf_str.s
    lda WDAY_NAMES+1,y
    sta.z sf_str.s+1
    jsr sf_str
    jmp __b5
  __b10:
    // sf_abbr(WDAY_ABBR, wd)
    ldx.z wd
    lda #<WDAY_ABBR
    sta.z sf_abbr.table
    lda #>WDAY_ABBR
    sta.z sf_abbr.table+1
    jsr sf_abbr
    jmp __b5
  .segment Data
    __57: .text "PM"
    .byte 0
    __56: .text "AM"
    .byte 0
}
.segment Code
// Configures the CIA#1 TOD clock for the mains frequency (50 or 60).
// void tod_set_frequency(char hz)
tod_set_frequency: {
    // uint8_t cra = CIA1->TIMER_A_CONTROL & 0x7f
    lda #$7f
    and CIA1+OFFSET_STRUCT_MOS6526_CIA_TIMER_A_CONTROL
    // cra |= CIA_TIMER_CONTROL_A_TOD_50HZ
    ora #CIA_TIMER_CONTROL_A_TOD_50HZ
    // CIA1->TIMER_A_CONTROL = cra
    sta CIA1+OFFSET_STRUCT_MOS6526_CIA_TIMER_A_CONTROL
    // }
    rts
}
// Sets the calendar time and (re)starts the CIA#1 TOD clock at that time.
// void set_time(unsigned long t)
set_time: {
    .const t = $6553f100
    .label sod = $26
    .label h = $4d
    .label m = $40
    .label s = $3b
    .label __11 = $21
    .label __14 = $2f
    .label __21 = $2f
    .label __22 = $2f
    .label __23 = $2f
    .label __24 = $2f
    .label __25 = $2f
    .label __26 = $2f
    .label __27 = $2f
    // split_days(t)
    lda #<t
    sta.z split_days.t
    lda #>t
    sta.z split_days.t+1
    lda #<t>>$10
    sta.z split_days.t+2
    lda #>t>>$10
    sta.z split_days.t+3
    jsr split_days
    // uint32_t sod = split_sod
    lda.z split_sod
    sta.z sod
    lda.z split_sod+1
    sta.z sod+1
    lda.z split_sod+2
    sta.z sod+2
    lda.z split_sod+3
    sta.z sod+3
    // time_day_base = t - sod
    lda #<t
    sec
    sbc.z sod
    sta.z time_day_base
    lda #>t
    sbc.z sod+1
    sta.z time_day_base+1
    lda #<t>>$10
    sbc.z sod+2
    sta.z time_day_base+2
    lda #>t>>$10
    sbc.z sod+3
    sta.z time_day_base+3
    // time_last_sod = sod
    lda.z sod
    sta.z time_last_sod
    lda.z sod+1
    sta.z time_last_sod+1
    lda.z sod+2
    sta.z time_last_sod+2
    lda.z sod+3
    sta.z time_last_sod+3
    // split_hms(sod)
    jsr split_hms
    // uint8_t h = split_h
    lda.z split_h
    sta.z h
    // uint8_t m = split_m
    lda.z split_m
    sta.z m
    // uint8_t s = split_s
    lda.z split_s
    sta.z s
    // uint8_t h12 = h % 12
    lda.z h
    asl
    asl
    sta.z __21
    lda #0
    rol
    sta.z __21+1
    lda.z h
    clc
    adc.z __22
    sta.z __22
    bcc !+
    inc.z __22+1
  !:
    asl.z __23
    rol.z __23+1
    asl.z __23
    rol.z __23+1
    lda.z h
    clc
    adc.z __24
    sta.z __24
    bcc !+
    inc.z __24+1
  !:
    asl.z __25
    rol.z __25+1
    asl.z __25
    rol.z __25+1
    lda.z h
    clc
    adc.z __26
    sta.z __26
    bcc !+
    inc.z __26+1
  !:
    asl.z __27
    rol.z __27+1
    lda.z h
    clc
    adc.z __14
    sta.z __14
    bcc !+
    inc.z __14+1
  !:
    lda.z __14+1
    lsr
    lsr
    lsr
    sta.z __11
    asl
    clc
    adc.z __11
    asl
    asl
    // The TOD registers are 12-hour BCD with the PM flag in bit 7
    eor #$ff
    sec
    adc.z h
    // if (h12 == 0)
    bne __b1
    lda #$c
  __b1:
    // uint8_t hb = bin_to_bcd8(h12)
    tax
    jsr bin_to_bcd8
    // uint8_t hb = bin_to_bcd8(h12)
    tax
    // if (h >= 12)
    lda.z h
    cmp #$c
    bcc __b2
    // hb |= 0x80
    txa
    ora #$80
    tax
  __b2:
    // CIA1->TIMER_B_CONTROL &= 0x7f
    // Make sure the TOD registers (not the alarm) are written
    lda #$7f
    and CIA1+OFFSET_STRUCT_MOS6526_CIA_TIMER_B_CONTROL
    sta CIA1+OFFSET_STRUCT_MOS6526_CIA_TIMER_B_CONTROL
    // CIA1->TOD_HOURS = hb
    // Writing the hours stops the clock, writing the tenths starts it
    stx CIA1+OFFSET_STRUCT_MOS6526_CIA_TOD_HOURS
    // bin_to_bcd8(m)
    ldx.z m
    jsr bin_to_bcd8
    // bin_to_bcd8(m)
    // CIA1->TOD_MIN = bin_to_bcd8(m)
    sta CIA1+OFFSET_STRUCT_MOS6526_CIA_TOD_MIN
    // bin_to_bcd8(s)
    ldx.z s
    jsr bin_to_bcd8
    // bin_to_bcd8(s)
    // CIA1->TOD_SEC = bin_to_bcd8(s)
    sta CIA1+OFFSET_STRUCT_MOS6526_CIA_TOD_SEC
    // CIA1->TOD_10THS = 0
    lda #0
    sta CIA1+OFFSET_STRUCT_MOS6526_CIA_TOD_10THS
    // }
    rts
}
// Returns the current calendar time as seconds since the epoch, read from the CIA#1 TOD clock.
// __zp($2b) unsigned long time(unsigned long *timer)
time: {
    .label __8 = $4e
    .label m = $21
    .label s = $1e
    .label sod = $2b
    .label return = $2b
    .label __17 = $4e
    .label __18 = $47
    .label __19 = $47
    .label __20 = $47
    .label __21 = $47
    .label __22 = $47
    .label __23 = $4e
    // uint8_t h = CIA1->TOD_HOURS
    // Reading the hours latches the clock, reading the tenths releases the latch again
    ldy CIA1+OFFSET_STRUCT_MOS6526_CIA_TOD_HOURS
    // uint8_t m = CIA1->TOD_MIN
    lda CIA1+OFFSET_STRUCT_MOS6526_CIA_TOD_MIN
    sta.z m
    // uint8_t s = CIA1->TOD_SEC
    lda CIA1+OFFSET_STRUCT_MOS6526_CIA_TOD_SEC
    sta.z s
    // asm
    lda $dc08
    // uint8_t hr = bcd8_to_bin(h & 0x1f)
    ldx #$1f
    tya
    axs #0
    jsr bcd8_to_bin
    // uint8_t hr = bcd8_to_bin(h & 0x1f)
    tax
    // if (hr == 12)
    cpx #$c
    bne __b1
    ldx #0
  __b1:
    // h & 0x80
    tya
    and #$80
    // if (h & 0x80)
    beq __b2
    // hr += 12
    txa
    axs #-[$c]
  __b2:
    // uint32_t sod = mul16u(hr, SECS_PER_HOUR)
    txa
    sta.z mul16u.a
    lda #0
    sta.z mul16u.a+1
    lda #<$e10
    sta.z mul16u.b
    lda #>$e10
    sta.z mul16u.b+1
    jsr mul16u
    // uint32_t sod = mul16u(hr, SECS_PER_HOUR)
    // bcd8_to_bin(m)
    ldx.z m
    jsr bcd8_to_bin
    // bcd8_to_bin(m)
    // (uint16_t)bcd8_to_bin(m) * SECS_PER_MINUTE
    sta.z __17
    lda #0
    sta.z __17+1
    lda.z __17
    asl
    sta.z __18
    lda.z __17+1
    rol
    sta.z __18+1
    clc
    lda.z __19
    adc.z __17
    sta.z __19
    lda.z __19+1
    adc.z __17+1
    sta.z __19+1
    asl.z __20
    rol.z __20+1
    clc
    lda.z __21
    adc.z __17
    sta.z __21
    lda.z __21+1
    adc.z __17+1
    sta.z __21+1
    asl.z __22
    rol.z __22+1
    clc
    lda.z __23
    adc.z __22
    sta.z __23
    lda.z __23+1
    adc.z __22+1
    sta.z __23+1
    asl.z __8
    rol.z __8+1
    asl.z __8
    rol.z __8+1
    // sod += (uint16_t)bcd8_to_bin(m) * SECS_PER_MINUTE
    lda.z sod
    clc
    adc.z __8
    sta.z sod
    lda.z sod+1
    adc.z __8+1
    sta.z sod+1
    lda.z sod+2
    adc #0
    sta.z sod+2
    lda.z sod+3
    adc #0
    sta.z sod+3
    // bcd8_to_bin(s)
    ldx.z s
    jsr bcd8_to_bin
    // bcd8_to_bin(s)
    // sod += bcd8_to_bin(s)
    clc
    adc.z sod
    sta.z sod
    lda.z sod+1
    adc #0
    sta.z sod+1
    lda.z sod+2
    adc #0
    sta.z sod+2
    lda.z sod+3
    adc #0
    sta.z sod+3
    // if (sod < time_last_sod)
    cmp.z time_last_sod+3
    bcc !+
    bne __b3
    lda.z sod+2
    cmp.z time_last_sod+2
    bcc !+
    bne __b3
    lda.z sod+1
    cmp.z time_last_sod+1
    bcc !+
    bne __b3
    lda.z sod
    cmp.z time_last_sod
    bcs __b3
  !:
    // time_day_base += SECS_PER_DAY
    clc
    lda.z time_day_base
    adc #<$15180
    sta.z time_day_base
    lda.z time_day_base+1
    adc #>$15180
    sta.z time_day_base+1
    lda.z time_day_base+2
    adc #<$15180>>$10
    sta.z time_day_base+2
    lda.z time_day_base+3
    adc #>$15180>>$10
    sta.z time_day_base+3
  __b3:
    // time_t now = time_day_base + sod
    clc
    lda.z return
    adc.z time_day_base
    sta.z return
    lda.z return+1
    adc.z time_day_base+1
    sta.z return+1
    lda.z return+2
    adc.z time_day_base+2
    sta.z return+2
    lda.z return+3
    adc.z time_day_base+3
    sta.z return+3
    // }
    rts
}
// Output a NUL-terminated string at the current cursor position
// void cputs(__zp($22) const char *s)
cputs: {
    .label s = $22
  __b1:
    // while(c=*s++)
    ldy #0
    lax (s),y
    inc.z s
    bne !+
    inc.z s+1
  !:
    cpx #0
    bne __b2
    // }
    rts
  __b2:
    // cputc(c)
    jsr cputc
    jmp __b1
}
// Output one character at the current cursor position
// Moves the cursor forward. Scrolls the entire screen if needed
// void cputc(__register(X) char c)
cputc: {
    // if(c=='\n')
    cpx #'\n'
    beq __b1
    // conio_line_text[conio_cursor_x] = c
    ldy.z conio_cursor_x
    txa
    sta (conio_line_text),y
    // conio_line_color[conio_cursor_x] = conio_textcolor
    lda #LIGHT_BLUE
    sta (conio_line_color),y
    // if(++conio_cursor_x==CONIO_WIDTH)
    inc.z conio_cursor_x
    lda #$28
    cmp.z conio_cursor_x
    bne __breturn
    // cputln()
    jmp cputln
  __breturn:
    // }
    rts
  __b1:
    // cputln()
    jmp cputln
}
// Converts broken-down time to the string "Www Mmm dd hh:mm:ss yyyy\n".
// char * asctime_r(__zp($1a) const struct tm *tp, char *buf)
asctime_r: {
    .label mn = $46
    .label p = $1c
    .label tp = $1a
    // uint8_t wd = tp->tm_wday
    ldy #OFFSET_STRUCT_TM_TM_WDAY
    lax (tp),y
    // uint8_t mn = tp->tm_mon
    ldy #OFFSET_STRUCT_TM_TM_MON
    lda (tp),y
    sta.z mn
    // if (wd >= countof(WDAY_NAMES))
    cpx #7
    bcc __b1
    ldx #0
  __b1:
    // if (mn >= countof(MON_NAMES))
    lda.z mn
    cmp #$c
    bcc __b2
    lda #0
    sta.z mn
  __b2:
    // char* p = put3(buf, WDAY_ABBR, wd)
    lda #<main.buf
    sta.z put3.p
    lda #>main.buf
    sta.z put3.p+1
    lda #<WDAY_ABBR
    sta.z put3.table
    lda #>WDAY_ABBR
    sta.z put3.table+1
    jsr put3
    // char* p = put3(buf, WDAY_ABBR, wd)
    // *p++ = ' '
    lda #' '
    ldy #0
    sta (p),y
    // *p++ = ' ';
    inc.z p
    bne !+
    inc.z p+1
  !:
    // put3(p, MON_ABBR, mn)
    ldx.z mn
    lda #<MON_ABBR
    sta.z put3.table
    lda #>MON_ABBR
    sta.z put3.table+1
    jsr put3
    // put3(p, MON_ABBR, mn)
    // p = put3(p, MON_ABBR, mn)
    // *p++ = ' '
    lda #' '
    ldy #0
    sta (p),y
    // *p++ = ' ';
    inc.z p
    bne !+
    inc.z p+1
  !:
    // put2(p, tp->tm_mday, ' ')
    ldy #OFFSET_STRUCT_TM_TM_MDAY
    lax (tp),y
    lda #' '
    sta.z put2.pad
    jsr put2
    // put2(p, tp->tm_mday, ' ')
    // p = put2(p, tp->tm_mday, ' ')
    // *p++ = ' '
    lda #' '
    ldy #0
    sta (p),y
    // *p++ = ' ';
    inc.z p
    bne !+
    inc.z p+1
  !:
    // put2(p, tp->tm_hour, '0')
    ldy #OFFSET_STRUCT_TM_TM_HOUR
    lax (tp),y
    lda #'0'
    sta.z put2.pad
    jsr put2
    // put2(p, tp->tm_hour, '0')
    // p = put2(p, tp->tm_hour, '0')
    // *p++ = ':'
    lda #':'
    ldy #0
    sta (p),y
    // *p++ = ':';
    inc.z p
    bne !+
    inc.z p+1
  !:
    // put2(p, tp->tm_min, '0')
    ldy #OFFSET_STRUCT_TM_TM_MIN
    lax (tp),y
    lda #'0'
    sta.z put2.pad
    jsr put2
    // put2(p, tp->tm_min, '0')
    // p = put2(p, tp->tm_min, '0')
    // *p++ = ':'
    lda #':'
    ldy #0
    sta (p),y
    // *p++ = ':';
    inc.z p
    bne !+
    inc.z p+1
  !:
    // put2(p, tp->tm_sec, '0')
    lax (tp),y
    lda #'0'
    sta.z put2.pad
    jsr put2
    // put2(p, tp->tm_sec, '0')
    // p = put2(p, tp->tm_sec, '0')
    // *p++ = ' '
    lda #' '
    ldy #0
    sta (p),y
    // *p++ = ' ';
    inc.z p
    bne !+
    inc.z p+1
  !:
    // put4(p, tp->tm_year + 1900)
    ldy #OFFSET_STRUCT_TM_TM_YEAR
    lda (put4.v),y
    clc
    adc #<$76c
    pha
    iny
    lda (put4.v),y
    adc #>$76c
    sta.z put4.v+1
    pla
    sta.z put4.v
    jsr put4
    // p = put4(p, tp->tm_year + 1900)
    // *p++ = '\n'
    lda #'\n'
    ldy #0
    sta (p),y
    // *p++ = '\n';
    inc.z p
    bne !+
    inc.z p+1
  !:
    // *p = 0
    tya
    sta (p),y
    // }
    rts
}
// Compares the string pointed to by str1 to the string pointed to by str2.
// __zp($e) int strcmp(const char *str1, __zp($10) const char *str2)
strcmp: {
    .label return = $e
    .label s1 = $22
    .label s2 = $10
    .label str2 = $10
    lda #<main.buf
    sta smc_ptr_1+1
    sta smc_ptr_2+1
    sta smc_ptr_3+1
    lda #>main.buf
    sta smc_ptr_1+2
    sta smc_ptr_2+2
    sta smc_ptr_3+2
  __b1:
    // while(*s1 == *s2)
    ldy #0
  smc_ptr_1:
    lda $ffff
    cmp (s2),y
    beq __b2
    // *s1 - *s2
    ldy #0
  smc_ptr_2:
    lda $ffff
    sec
    sbc (s2),y
    // return (int)(signed char)(*s1 - *s2);
    sta.z return
    ora #$7f
    bmi !+
    tya
  !:
    sta.z return+1
  __breturn:
    // }
    tax
    lda.z $e
    rts
  __b2:
    // if(*s1 == 0)
    ldy #0
  smc_ptr_3:
    lda $ffff
    bne __b4
    lda #<0
    sta.z return
    sta.z return+1
    jmp __breturn
  __b4:
    // s1++;
    inc smc_ptr_1+1
    inc smc_ptr_2+1
    inc smc_ptr_3+1
    bne !+
    inc smc_ptr_1+2
    inc smc_ptr_2+2
    inc smc_ptr_3+2
  !:
    // s2++;
    inc.z s2
    bne !+
    inc.z s2+1
  !:
    jmp __b1
}
// Splits t into the number of whole days since the epoch (returned) and the seconds of that day (split_sod).
// 86400 = 675 * 128, so the 32-bit division is done as a 32/16-bit division of t>>7.
// __zp(2) unsigned int split_days(__zp($42) unsigned long t)
split_days: {
    .label __1 = $35
    .label __2 = $3c
    .label __5 = $3c
    .label t = $42
    .label return = 2
    // div32u16u(t >> 7, 675)
    lda.z t
    asl
    lda.z t+1
    rol
    sta.z div32u16u.dividend
    lda.z t+2
    rol
    sta.z div32u16u.dividend+1
    lda.z t+3
    rol
    sta.z div32u16u.dividend+2
    lda #0
    rol
    sta.z div32u16u.dividend+3
    lda #<$2a3
    sta.z div32u16u.divisor
    lda #>$2a3
    sta.z div32u16u.divisor+1
    jsr div32u16u
    // div32u16u(t >> 7, 675)
    // uint16_t days = (uint16_t)div32u16u(t >> 7, 675)
    lda.z __1
    sta.z return
    lda.z __1+1
    sta.z return+1
    // (uint32_t)rem16u << 7
    lda.z divr16u.rem
    sta.z __5
    lda.z divr16u.rem+1
    sta.z __5+1
    lda #0
    sta.z __5+2
    sta.z __5+3
    lsr
    sta.z __2+3
    lda.z __2+1
    ror
    sta.z __2+2
    lda.z __2
    ror
    sta.z __2+1
    lda #0
    ror
    sta.z __2
    // t & 127
    lda #$7f
    and.z t
    // ((uint32_t)rem16u << 7) | (t & 127)
    ora.z __2
    sta.z split_sod
    lda.z __2+1
    sta.z split_sod+1
    lda.z __2+2
    sta.z split_sod+2
    lda.z __2+3
    sta.z split_sod+3
    // }
    rts
}
// Splits seconds of a day into split_h, split_m and split_s
// void split_hms(__zp($26) unsigned long sod)
split_hms: {
    .label __0 = $35
    .label __1 = 7
    .label sod = $26
    // div32u16u(sod, 3600)
    lda #<$e10
    sta.z div32u16u.divisor
    lda #>$e10
    sta.z div32u16u.divisor+1
    jsr div32u16u
    // div32u16u(sod, 3600)
    // split_h = (uint8_t)div32u16u(sod, 3600)
    lda.z __0
    sta.z split_h
    // div16u(rem16u, 60)
    lda.z divr16u.rem
    sta.z div16u.dividend
    lda.z divr16u.rem+1
    sta.z div16u.dividend+1
    lda #<$3c
    sta.z div16u.divisor
    lda #>$3c
    sta.z div16u.divisor+1
    jsr div16u
    // div16u(rem16u, 60)
    // split_m = (uint8_t)div16u(rem16u, 60)
    lda.z __1
    sta.z split_m
    // split_s = (uint8_t)rem16u
    lda.z divr16u.rem
    sta.z split_s
    // }
    rts
}
// Performs division on two 16 bit unsigned ints
// Returns the quotient dividend/divisor.
// The remainder will be set into the global variable rem16u
// Implemented using simple binary division
// __zp(7) unsigned int div16u(__zp($a) unsigned int dividend, __zp($e) unsigned int divisor)
div16u: {
    .label dividend = $a
    .label return = 7
    .label divisor = $e
    // divr16u(dividend, divisor, 0)
    lda.z dividend
    sta.z divr16u.dividend
    lda.z dividend+1
    sta.z divr16u.dividend+1
    lda.z divisor
    sta.z divr16u.divisor
    lda.z divisor+1
    sta.z divr16u.divisor+1
    lda #<0
    sta.z divr16u.rem
    sta.z divr16u.rem+1
    jmp divr16u
    // }
}
// Returns true if year is a leap year in the Gregorian calendar.
// __register(A) bool is_leap_year(__zp($a) unsigned int year)
is_leap_year: {
    .label century = 7
    .label year = $a
    // year & 3
    lda #3
    and.z year
    // if ((year & 3) != 0)
    beq __b1
    clc
    // }
    rts
  __b1:
    // uint16_t century = div16u(year, 100)
    lda #<$64
    sta.z div16u.divisor
    lda #>$64
    sta.z div16u.divisor+1
    jsr div16u
    // uint16_t century = div16u(year, 100)
    // if (rem16u != 0)
    lda.z divr16u.rem
    ora.z divr16u.rem+1
    beq __b2
    sec
    rts
  __b2:
    // century & 3
    lda #3
    and.z century
    // return (century & 3) == 0;
    beq !+
    lda #1
  !:
    eor #1
    lsr
    rts
}
// Number of days from 1970-01-01 to January 1 of the given year (1970 or later)
// __zp($a) unsigned int days_before_year(__zp($1a) unsigned int year)
days_before_year: {
    .label __3 = 4
    .label __4 = $a
    .label __5 = $a
    .label __6 = $a
    .label __7 = 7
    .label __8 = $a
    .label __10 = $1a
    .label year = $1a
    .label return = $a
    .label y = $a
    .label centuries = 7
    .label __11 = 4
    .label __12 = 4
    .label __13 = 4
    .label __14 = 4
    .label __15 = 4
    .label __16 = 4
    .label __17 = 4
    .label __18 = 4
    .label __19 = 4
    // uint16_t y = year - 1
    lda.z year
    sec
    sbc #1
    sta.z y
    lda.z year+1
    sbc #0
    sta.z y+1
    // uint16_t centuries = div16u(y, 100)
    lda #<$64
    sta.z div16u.divisor
    lda #>$64
    sta.z div16u.divisor+1
    jsr div16u
    // uint16_t centuries = div16u(y, 100)
    // (uint16_t)(year - 1970) * 365
    lda.z __10
    sec
    sbc #<$7b2
    sta.z __10
    lda.z __10+1
    sbc #>$7b2
    sta.z __10+1
    lda.z __10
    asl
    sta.z __11
    lda.z __10+1
    rol
    sta.z __11+1
    asl.z __11
    rol.z __11+1
    clc
    lda.z __12
    adc.z __10
    sta.z __12
    lda.z __12+1
    adc.z __10+1
    sta.z __12+1
    asl.z __13
    rol.z __13+1
    clc
    lda.z __14
    adc.z __10
    sta.z __14
    lda.z __14+1
    adc.z __10+1
    sta.z __14+1
    asl.z __15
    rol.z __15+1
    asl.z __15
    rol.z __15+1
    clc
    lda.z __16
    adc.z __10
    sta.z __16
    lda.z __16+1
    adc.z __10+1
    sta.z __16+1
    asl.z __17
    rol.z __17+1
    clc
    lda.z __18
    adc.z __10
    sta.z __18
    lda.z __18+1
    adc.z __10+1
    sta.z __18+1
    asl.z __19
    rol.z __19+1
    asl.z __19
    rol.z __19+1
    clc
    lda.z __3
    adc.z __10
    sta.z __3
    lda.z __3+1
    adc.z __10+1
    sta.z __3+1
    // y >> 2
    lsr.z __4+1
    ror.z __4
    lsr.z __4+1
    ror.z __4
    // (uint16_t)(year - 1970) * 365 + (y >> 2)
    clc
    lda.z __5
    adc.z __3
    sta.z __5
    lda.z __5+1
    adc.z __3+1
    sta.z __5+1
    // (uint16_t)(year - 1970) * 365 + (y >> 2) - centuries
    lda.z __6
    sec
    sbc.z centuries
    sta.z __6
    lda.z __6+1
    sbc.z centuries+1
    sta.z __6+1
    // centuries >> 2
    lsr.z __7+1
    ror.z __7
    lsr.z __7+1
    ror.z __7
    // (uint16_t)(year - 1970) * 365 + (y >> 2) - centuries + (centuries >> 2)
    clc
    lda.z __8
    adc.z __7
    sta.z __8
    lda.z __8+1
    adc.z __7+1
    sta.z __8+1
    // (uint16_t)(year - 1970) * 365 + (y >> 2) - centuries + (centuries >> 2) - 477
    lda.z return
    sec
    sbc #<$1dd
    sta.z return
    lda.z return+1
    sbc #>$1dd
    sta.z return+1
    // }
    rts
}
// Perform binary multiplication of two unsigned 16-bit unsigned ints into a 32-bit unsigned long
// __zp($2b) unsigned long mul16u(__zp($10) unsigned int a, __zp(7) unsigned int b)
mul16u: {
    .label a = $10
    .label return = $2b
    .label mb = $26
    .label res = $2b
    .label b = 7
    // unsigned long mb = b
    lda.z b
    sta.z mb
    lda.z b+1
    sta.z mb+1
    lda #0
    sta.z mb+2
    sta.z mb+3
    sta.z res
    sta.z res+1
    sta.z res+2
    sta.z res+3
  __b1:
    // while(a!=0)
    lda.z a
    ora.z a+1
    bne __b2
    // }
    rts
  __b2:
    // a&1
    lda #1
    and.z a
    // if( (a&1) != 0)
    beq __b3
    // res = res + mb
    clc
    lda.z res
    adc.z mb
    sta.z res
    lda.z res+1
    adc.z mb+1
    sta.z res+1
    lda.z res+2
    adc.z mb+2
    sta.z res+2
    lda.z res+3
    adc.z mb+3
    sta.z res+3
  __b3:
    // a = a>>1
    lsr.z a+1
    ror.z a
    // mb = mb<<1
    asl.z mb
    rol.z mb+1
    rol.z mb+2
    rol.z mb+3
    jmp __b1
}
// Appends one character to the strftime() output
// void sf_char(__register(X) char c)
sf_char: {
    // if (sf.left != 0)
    lda sf+2
    ora sf+2+1
    bne __b1
    // sf.ok = false
    lda #0
    sta sf+4
    // }
    rts
  __b1:
    // *sf.ptr++ = c
    txa
    ldy sf
    sty.z $fe
    ldy sf+1
    sty.z $ff
    ldy #0
    sta ($fe),y
    // *sf.ptr++ = c;
    inc sf
    bne !+
    inc sf+1
  !:
    // sf.left--;
    lda sf+2
    bne !+
    dec sf+2+1
  !:
    dec sf+2
    rts
}
// Appends the number v right-aligned to at least width characters, filled with pad
// void sf_num(__zp(7) unsigned int v, __zp($15) char width, __zp($14) char pad)
sf_num: {
    .label __1 = $2f
    .label __2 = $16
    .label __4 = $18
    .label v = 7
    .label b = 4
    .label first = 6
    .label i = 9
    .label i1 = 6
    .label width = $15
    .label pad = $14
    // bcd16_t b = bin_to_bcd16(v)
    lda.z v
    sta.z bin_to_bcd16.v
    lda.z v+1
    sta.z bin_to_bcd16.v+1
  // Convert to BCD (a single division) and print the digits by nibble, skipping leading zeros
    jsr bin_to_bcd16
    // bcd16_t b = bin_to_bcd16(v)
    // b >> 12
    ldy #$c
    lda.z b
    sta.z __1
    lda.z b+1
    sta.z __1+1
    cpy #0
    beq !e+
  !:
    lsr.z __1+1
    ror.z __1
    dey
    bne !-
  !e:
    // d[0] = (uint8_t)(b >> 12)
    lda.z __1
    sta d
    // b >> 8
    lda.z b+1
    sta.z __2
    lda #0
    sta.z __2+1
    // (b >> 8) & 0x0f
    lda #$f
    and.z __2
    // d[1] = (uint8_t)((b >> 8) & 0x0f)
    sta d+1
    // b >> 4
    lda.z b+1
    lsr
    sta.z __4+1
    lda.z b
    ror
    sta.z __4
    lsr.z __4+1
    ror.z __4
    lsr.z __4+1
    ror.z __4
    lsr.z __4+1
    ror.z __4
    // (b >> 4) & 0x0f
    lda #$f
    and.z __4
    // d[2] = (uint8_t)((b >> 4) & 0x0f)
    sta d+2
    // b & 0x0f
    lda #$f
    and.z b
    // d[3] = (uint8_t)(b & 0x0f)
    sta d+3
    lda #0
    sta.z first
  __b1:
    // while (first < 3 && d[first] == 0)
    lda.z first
    cmp #3
    bcs __b3
    tay
    lda d,y
    beq __b2
  __b3:
    // uint8_t i = 4 - first
    lda #4
    sec
    sbc.z first
    sta.z i
  __b4:
    // for (uint8_t i = 4 - first; i < width; i++)
    lda.z i
    cmp.z width
    bcc __b5
  __b6:
    // for (uint8_t i = first; i < 4; i++)
    lda.z i1
    cmp #4
    bcc __b7
    // }
    rts
  __b7:
    // sf_char((char)('0' + d[i]))
    ldx.z i1
    lda d,x
    tax
    axs #-['0']
    jsr sf_char
    // for (uint8_t i = first; i < 4; i++)
    inc.z i1
    jmp __b6
  __b5:
    // sf_char(pad)
    ldx.z pad
    jsr sf_char
    // for (uint8_t i = 4 - first; i < width; i++)
    inc.z i
    jmp __b4
  __b2:
    // first++;
    inc.z first
    jmp __b1
  .segment Data
    d: .fill 4, 0
}
.segment Code
// Returns year modulo 100
// __register(A) char year_mod100(__zp($a) unsigned int year)
year_mod100: {
    .label year = $a
    // div16u(year, 100)
    lda #<$64
    sta.z div16u.divisor
    lda #>$64
    sta.z div16u.divisor+1
    jsr div16u
    // return (uint8_t)rem16u;
    lda.z divr16u.rem
    // }
    rts
}
// Appends the time as "HH:MM:SS" (%T), or "HH:MM" (%R) if withSeconds is false
// void sf_time(const struct tm *tp, __zp($4d) bool withSeconds)
sf_time: {
    .label withSeconds = $4d
    // sf_num(tp->tm_hour, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_HOUR
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    // sf_char(':')
    ldx #':'
    jsr sf_char
    // sf_num(tp->tm_min, 2, '0')
    lda.z main.tm+OFFSET_STRUCT_TM_TM_MIN
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    // if (withSeconds)
    lda.z withSeconds
    bne __b1
    // }
    rts
  __b1:
    // sf_char(':')
    ldx #':'
    jsr sf_char
    // sf_num(tp->tm_sec, 2, '0')
    lda.z main.tm
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jmp sf_num
}
// Appends a zero-terminated string
// void sf_str(__zp($10) const char *s)
sf_str: {
    .label s = $10
  __b1:
    // while (*s)
    ldy #0
    lda (s),y
    bne __b2
    // }
    rts
  __b2:
    // sf_char(*s++)
    ldy #0
    lax (s),y
    jsr sf_char
    // sf_char(*s++);
    inc.z s
    bne !+
    inc.z s+1
  !:
    jmp __b1
}
// Appends the date as "MM/DD/YY" (%D)
// void sf_date_us(const struct tm *tp)
sf_date_us: {
    .label tp = main.tm
    // sf_num(tp->tm_mon + 1, 2, '0')
    lda.z tp+OFFSET_STRUCT_TM_TM_MON
    clc
    adc #1
    sta.z sf_num.v
    lda #0
    adc #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    // sf_char('/')
    ldx #'/'
    jsr sf_char
    // sf_num(tp->tm_mday, 2, '0')
    lda.z tp+OFFSET_STRUCT_TM_TM_MDAY
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jsr sf_num
    // sf_char('/')
    ldx #'/'
    jsr sf_char
    // year_mod100(tp->tm_year + 1900)
    lda.z tp+OFFSET_STRUCT_TM_TM_YEAR
    clc
    adc #<$76c
    sta.z year_mod100.year
    lda.z tp+OFFSET_STRUCT_TM_TM_YEAR+1
    adc #>$76c
    sta.z year_mod100.year+1
    jsr year_mod100
    // year_mod100(tp->tm_year + 1900)
    // sf_num(year_mod100(tp->tm_year + 1900), 2, '0')
    sta.z sf_num.v
    lda #0
    sta.z sf_num.v+1
    lda #'0'
    sta.z sf_num.pad
    lda #2
    sta.z sf_num.width
    jmp sf_num
    // }
}
// Appends a 3-letter abbreviation from a table of 3-letter entries
// void sf_abbr(__zp($a) const char *table, __register(X) char idx)
sf_abbr: {
    .label e = $a
    .label table = $a
    // idx * 3
    txa
    asl
    stx.z $ff
    clc
    adc.z $ff
    // const char* e = table + idx * 3
    clc
    adc.z e
    sta.z e
    bcc !+
    inc.z e+1
  !:
    // sf_char(e[0])
    ldy #0
    lax (e),y
    jsr sf_char
    // sf_char(e[1])
    ldy #1
    lax (e),y
    jsr sf_char
    // sf_char(e[2])
    ldy #2
    lax (e),y
    jmp sf_char
    // }
}
// __register(A) char bin_to_bcd8(__register(X) char val)
bin_to_bcd8: {
    ldy #0
  __b1:
    // while(val >= 10)
    cpx #$a
    bcs __b2
    // tens << 4
    tya
    asl
    asl
    asl
    asl
    // (tens << 4) | val
    stx.z $ff
    ora.z $ff
    // }
    rts
  __b2:
    // tens++;
    iny
    // val -= 10
    txa
    axs #$a
    jmp __b1
}
// __register(A) char bcd8_to_bin(__register(X) char bcd)
bcd8_to_bin: {
    .label __0 = $46
    .label __1 = $15
    // bcd >> 4
    txa
    lsr
    lsr
    lsr
    lsr
    sta.z __0
    // (bcd >> 4) * 10
    asl
    asl
    clc
    adc.z __0
    asl
    sta.z __1
    // bcd & 0x0f
    txa
    and #$f
    // return (uint8_t)(((bcd >> 4) * 10) + (bcd & 0x0f));
    clc
    adc.z __1
    // }
    rts
}
// Print a newline
cputln: {
    // conio_line_text +=  CONIO_WIDTH
    lda #$28
    clc
    adc.z conio_line_text
    sta.z conio_line_text
    bcc !+
    inc.z conio_line_text+1
  !:
    // conio_line_color += CONIO_WIDTH
    lda #$28
    clc
    adc.z conio_line_color
    sta.z conio_line_color
    bcc !+
    inc.z conio_line_color+1
  !:
    // conio_cursor_x = 0
    lda #0
    sta.z conio_cursor_x
    // conio_cursor_y++;
    inc.z conio_cursor_y
    // cscroll()
    jmp cscroll
    // }
}
// Writes an abbreviation from a table of 3-letter entries
// __zp($1c) char * put3(__zp($1c) char *p, __zp($a) const char *table, __register(X) char idx)
put3: {
    .label return = $1c
    .label p = $1c
    .label e = $a
    .label table = $a
    // idx * 3
    txa
    asl
    stx.z $ff
    clc
    adc.z $ff
    // const char* e = table + idx * 3
    clc
    adc.z e
    sta.z e
    bcc !+
    inc.z e+1
  !:
    // *p++ = e[0]
    ldy #0
    lda (e),y
    sta (p),y
    // *p++ = e[0];
    inc.z p
    bne !+
    inc.z p+1
  !:
    // *p++ = e[1]
    ldy #1
    lda (e),y
    ldy #0
    sta (p),y
    // *p++ = e[1];
    inc.z p
    bne !+
    inc.z p+1
  !:
    // *p++ = e[2]
    ldy #2
    lda (e),y
    ldy #0
    sta (p),y
    // *p++ = e[2];
    inc.z return
    bne !+
    inc.z return+1
  !:
    // }
    rts
}
// Writes a number 0..99 with exactly two characters. The leading zero is replaced by pad.
// __zp($1c) char * put2(__zp($1c) char *p, __register(X) char v, __zp($15) char pad)
put2: {
    .label p = $1c
    .label return = $1c
    .label pad = $15
    // bin_to_bcd8(v)
    jsr bin_to_bcd8
    // bin_to_bcd8(v)
    // put_bcd8(p, bin_to_bcd8(v), pad)
    sta.z put_bcd8.b
    ldx.z pad
    jmp put_bcd8
    // put_bcd8(p, bin_to_bcd8(v), pad)
    // }
}
// Writes a year 0..9999 with four digits
// __zp($1c) char * put4(__zp($1c) char *p, __zp($1a) unsigned int v)
put4: {
    .label __1 = $2f
    .label p = $1c
    .label v = $1a
    .label return = $1c
    .label b = 4
    // bcd16_t b = bin_to_bcd16(v)
    lda.z v
    sta.z bin_to_bcd16.v
    lda.z v+1
    sta.z bin_to_bcd16.v+1
    jsr bin_to_bcd16
    // bcd16_t b = bin_to_bcd16(v)
    // b >> 8
    lda.z b+1
    // put_bcd8(p, (bcd8_t)(b >> 8), '0')
    sta.z put_bcd8.b
    ldx #'0'
    jsr put_bcd8
    // put_bcd8(p, (bcd8_t)(b >> 8), '0')
    // p = put_bcd8(p, (bcd8_t)(b >> 8), '0')
    // put_bcd8(p, (bcd8_t)b, '0')
    lda.z b
    sta.z put_bcd8.b
    ldx #'0'
    jmp put_bcd8
    // put_bcd8(p, (bcd8_t)b, '0')
    // }
}
// Divide unsigned 32-bit unsigned long dividend with a 16-bit unsigned int divisor
// The 16-bit unsigned int remainder can be found in rem16u after the division
// __zp($35) unsigned long div32u16u(__zp($26) unsigned long dividend, __zp($1c) unsigned int divisor)
div32u16u: {
    .label __0 = $16
    .label __2 = $18
    .label quotient_hi = $1f
    .label quotient_lo = 7
    .label return = $35
    .label dividend = $26
    .label divisor = $1c
    // WORD1(dividend)
    lda.z dividend+2
    sta.z __0
    lda.z dividend+3
    sta.z __0+1
    // unsigned int quotient_hi = divr16u(WORD1(dividend), divisor, 0)
    lda.z __0
    sta.z divr16u.dividend
    lda.z __0+1
    sta.z divr16u.dividend+1
    lda.z divisor
    sta.z divr16u.divisor
    lda.z divisor+1
    sta.z divr16u.divisor+1
    lda #<0
    sta.z divr16u.rem
    sta.z divr16u.rem+1
    jsr divr16u
    sta.z divr16u.return_1
    stx.z divr16u.return_1+1
    // WORD0(dividend)
    lda.z dividend
    sta.z __2
    sta.z divr16u.dividend
    lda.z dividend+1
    sta.z __2+1
    sta.z divr16u.dividend+1
    // unsigned int quotient_lo = divr16u(WORD0(dividend), divisor, rem16u)
    lda.z divisor
    sta.z divr16u.divisor
    lda.z divisor+1
    sta.z divr16u.divisor+1
    jsr divr16u
    // unsigned long quotient = MAKELONG( quotient_hi, quotient_lo )
    lda.z quotient_hi
    sta.z return+2
    lda.z quotient_hi+1
    sta.z return+3
    lda.z quotient_lo
    sta.z return
    lda.z quotient_lo+1
    sta.z return+1
    // }
    rts
}
// Performs division on two 16 bit unsigned ints and an initial remainder
// Returns the quotient dividend/divisor.
// The final remainder will be set into the global variable rem16u
// Implemented using fast 6502 carry-chain binary division (~4x speedup)
// __zp(7) unsigned int divr16u(__zp($c) volatile unsigned int dividend, __zp($12) volatile unsigned int divisor, __zp($24) volatile unsigned int rem)
divr16u: {
    .label dividend = $c
    .label divisor = $12
    .label rem = $24
    .label return = 7
    .label return_1 = $1f
    // asm
    ldx #$10
  loop:
    asl dividend
    rol dividend+1
    rol rem
    rol rem+1
    bcs overflow
    lda rem
    sec
    sbc divisor
    tay
    lda rem+1
    sbc divisor+1
    bcc skip
    sta rem+1
    sty rem
    inc dividend
    jmp skip
  overflow:
    lda rem
    sec
    sbc divisor
    sta rem
    lda rem+1
    sbc divisor+1
    sta rem+1
    inc dividend
  skip:
    dex
    bne loop
    // return dividend;
    lda.z dividend
    sta.z return
    lax.z dividend+1
    sta.z return+1
    // }
    lda.z 7
    rts
}
// Converts a number to 4-digit BCD (values above 9999 are clamped). Needs a single division.
// __zp(4) unsigned int bin_to_bcd16(__zp($a) unsigned int v)
bin_to_bcd16: {
    .label __2 = 7
    .label __4 = 4
    .label __7 = 4
    .label lo = $1e
    .label return = 4
    .label v = $a
    // if (v > 9999)
    lda.z v+1
    cmp #>$2710
    bcc __b1
    bne !+
    lda.z v
    cmp #<$2710
    bcc __b1
  !:
    lda #<$270f
    sta.z v
    lda #>$270f
    sta.z v+1
  __b1:
    // div16u(v, 100)
    lda #<$64
    sta.z div16u.divisor
    lda #>$64
    sta.z div16u.divisor+1
    jsr div16u
    // div16u(v, 100)
    // uint8_t hi = (uint8_t)div16u(v, 100)
    ldx.z __2
    // uint8_t lo = (uint8_t)rem16u
    lda.z divr16u.rem
    sta.z lo
    // bin_to_bcd8(hi)
    jsr bin_to_bcd8
    // bin_to_bcd8(hi)
    // (bcd16_t)bin_to_bcd8(hi) << 8
    sta.z __4+1
    lda #0
    sta.z __4
    // bin_to_bcd8(lo)
    ldx.z lo
    jsr bin_to_bcd8
    // bin_to_bcd8(lo)
    // ((bcd16_t)bin_to_bcd8(hi) << 8) | bin_to_bcd8(lo)
    ora.z return
    sta.z return
    // }
    rts
}
// Scroll the entire screen if the cursor is beyond the last line
cscroll: {
    // if(conio_cursor_y==CONIO_HEIGHT)
    lda #$19
    cmp.z conio_cursor_y
    bne __breturn
    // memcpy(CONIO_SCREEN_TEXT, CONIO_SCREEN_TEXT+CONIO_WIDTH, CONIO_BYTES-CONIO_WIDTH)
    lda #<DEFAULT_SCREEN
    sta.z memcpy.destination
    lda #>DEFAULT_SCREEN
    sta.z memcpy.destination+1
    lda #<DEFAULT_SCREEN+$28
    sta.z memcpy.source
    lda #>DEFAULT_SCREEN+$28
    sta.z memcpy.source+1
    jsr memcpy
    // memcpy(CONIO_SCREEN_COLORS, CONIO_SCREEN_COLORS+CONIO_WIDTH, CONIO_BYTES-CONIO_WIDTH)
    lda #<COLORRAM
    sta.z memcpy.destination
    lda #>COLORRAM
    sta.z memcpy.destination+1
    lda #<COLORRAM+$28
    sta.z memcpy.source
    lda #>COLORRAM+$28
    sta.z memcpy.source+1
    jsr memcpy
    // memset(CONIO_SCREEN_TEXT+CONIO_BYTES-CONIO_WIDTH, ' ', CONIO_WIDTH)
    ldx #' '
    lda #<DEFAULT_SCREEN+$19*$28-$28
    sta.z memset.str
    lda #>DEFAULT_SCREEN+$19*$28-$28
    sta.z memset.str+1
    jsr memset
    // memset(CONIO_SCREEN_COLORS+CONIO_BYTES-CONIO_WIDTH, conio_textcolor, CONIO_WIDTH)
    ldx #LIGHT_BLUE
    lda #<COLORRAM+$19*$28-$28
    sta.z memset.str
    lda #>COLORRAM+$19*$28-$28
    sta.z memset.str+1
    jsr memset
    // conio_line_text -= CONIO_WIDTH
    sec
    lda.z conio_line_text
    sbc #$28
    sta.z conio_line_text
    lda.z conio_line_text+1
    sbc #0
    sta.z conio_line_text+1
    // conio_line_color -= CONIO_WIDTH
    sec
    lda.z conio_line_color
    sbc #$28
    sta.z conio_line_color
    lda.z conio_line_color+1
    sbc #0
    sta.z conio_line_color+1
    // conio_cursor_y--;
    dec.z conio_cursor_y
  __breturn:
    // }
    rts
}
// Writes a 2-digit BCD number with exactly two characters. The leading zero is replaced by pad.
// __zp($1c) char * put_bcd8(__zp($1c) char *p, __zp($14) char b, __register(X) char pad)
put_bcd8: {
    .label p = $1c
    .label return = $1c
    .label b = $14
    // uint8_t tens = b >> 4
    lda.z b
    lsr
    lsr
    lsr
    lsr
    // (tens == 0) ? pad : (char)('0' + tens)
    beq __b2
    tax
    axs #-['0']
  __b2:
    // *p++ = (tens == 0) ? pad : (char)('0' + tens)
    txa
    ldy #0
    sta (p),y
    // *p++ = (tens == 0) ? pad : (char)('0' + tens);
    inc.z p
    bne !+
    inc.z p+1
  !:
    // b & 0x0f
    lda #$f
    and.z b
    // '0' + (b & 0x0f)
    clc
    adc #'0'
    // *p++ = (char)('0' + (b & 0x0f))
    sta (p),y
    // *p++ = (char)('0' + (b & 0x0f));
    inc.z return
    bne !+
    inc.z return+1
  !:
    // }
    lda.z $1c
    ldx.z $1c+1
    rts
}
// Copy block of memory (forwards)
// Optimized with 256-byte page copy loops for maximum 6502 throughput (~13 cycles/byte).
// void * memcpy(__zp(4) void *destination, __zp(2) void *source, unsigned int num)
memcpy: {
    .const rem = $ff&$19*$28-$28
    .label src = 2
    .label dst = 4
    .label source = 2
    .label destination = 4
    ldx #$19*$28-$28>>8
  __b2:
    ldy #0
  __b3:
    // dst[i] = src[i]
    lda (src),y
    sta (dst),y
    // i++;
    iny
    // while(i != 0)
    bne __b3
    // src += 256
    clc
    lda.z src+1
    adc #>$100
    sta.z src+1
    // dst += 256
    clc
    lda.z dst+1
    adc #>$100
    sta.z dst+1
    // pages--;
    dex
    // while(pages != 0)
    bne __b2
    ldy #0
  __b5:
    // dst[i] = src[i]
    lda (src),y
    sta (dst),y
    // i++;
    iny
    // while(i != rem)
    cpy #rem
    bne __b5
    // }
    rts
}
// Copies the character c (an unsigned char) to the first num characters of the object pointed to by the argument str.
// Optimized with 256-byte page fill loops for maximum 6502 throughput (~10 cycles/byte).
// void * memset(__zp(2) void *str, __register(X) char c, unsigned int num)
memset: {
    .const rem = $28
    .label str = 2
    ldy #0
  __b1:
    // dst[i] = c
    txa
    sta (str),y
    // i++;
    iny
    // while(i != rem)
    cpy #rem
    bne __b1
    // }
    rts
}
.segment Data
  // Days in each month of a non-leap year
  MONTH_DAYS: .byte $1f, $1c, $1f, $1e, $1f, $1e, $1f, $1f, $1e, $1f, $1e, $1f
  // Days from January 1 to the first of each month in a non-leap year
  DAYS_BEFORE_MONTH: .byte 0, $1f, $3b, $5a, $78, $97, $b5, $d4, $f3, $11, $30, $4e, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1
  // 3-letter abbreviations, concatenated: entry i starts at i*3
  WDAY_ABBR: .text "SunMonTueWedThuFriSat"
  .byte 0
  MON_ABBR: .text "JanFebMarAprMayJunJulAugSepOctNovDec"
  .byte 0
  // Full names
  WDAY_NAMES: .word __5, __6, __7, __8, __9, __10, __11
  MON_NAMES: .word __12, __13, __14, __15, __16, __17, __18, __19, __20, __21, __22, __23
  __5: .text "Sunday"
  .byte 0
  __6: .text "Monday"
  .byte 0
  __7: .text "Tuesday"
  .byte 0
  __8: .text "Wednesday"
  .byte 0
  __9: .text "Thursday"
  .byte 0
  __10: .text "Friday"
  .byte 0
  __11: .text "Saturday"
  .byte 0
  __12: .text "January"
  .byte 0
  __13: .text "February"
  .byte 0
  __14: .text "March"
  .byte 0
  __15: .text "April"
  .byte 0
  __16: .text "May"
  .byte 0
  __17: .text "June"
  .byte 0
  __18: .text "July"
  .byte 0
  __19: .text "August"
  .byte 0
  __20: .text "September"
  .byte 0
  __21: .text "October"
  .byte 0
  __22: .text "November"
  .byte 0
  __23: .text "December"
  .byte 0
  s: .text "FAIL "
  .byte 0
  s2: .text @"\r\n"
  .byte 0
  // State of the running strftime() call
  sf: .fill SIZEOF_STRUCT___4, 0
