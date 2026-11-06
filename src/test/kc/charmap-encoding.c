// Tests custom character mapping via #pragma charmap and string suffixes

#pragma charmap(ui, screencode_mixed, "ä=27, ö=28, ü=29, Ä=91, Ö=92, Ü=93, ß=30")

// String with suffix
const char str_ui[] = "München"ui;

// String with suffix and no-zero-term
const char str_uiz[] = "Köln"uiz;

// Scope encoding
#pragma encoding(ui)
const char str_scope[] = "Grüße";
const char char_scope = 'ä';

char* const SCREEN = (char*)0x0400;

void main() {
    SCREEN[0] = str_ui[0];
    SCREEN[1] = str_uiz[0];
    SCREEN[2] = str_scope[0];
    SCREEN[3] = char_scope;
    SCREEN[4] = 'ü'ui;
}
