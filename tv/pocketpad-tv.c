/*
 * PocketPad TV receiver — the "PC side" of PROTOCOL.md v1, for Android TV.
 *
 * Runs on the TV as the adb `shell` user (no root): shell is in the `uhid`
 * group, so it can open /dev/uinput and create real kernel input devices.
 * Each connected phone gets its own virtual Xbox 360 pad (045e:028e, so
 * Android applies its stock Xbox key layout); trackpad mode drives a shared
 * virtual mouse. The phone app is unchanged: type the TV's IP and the
 * pairing code this prints.
 *
 * Build: arm-linux-gnueabihf-gcc -O2 -static -o pocketpad-tv pocketpad-tv.c
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define UDP_PORT 46821
#define TCP_PORT 46822
#define STATUS_PORT 46823 /* 127.0.0.1 only: the TV app polls it */
#define MAX_PLAYERS 4
#define STATE_TIMEOUT_MS 5000
#define TOKEN_FILE "/data/local/tmp/pocketpad-token.txt"

static void logf_(const char *fmt, ...) {
    va_list ap;
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    printf("%02d:%02d:%02d ", tm.tm_hour, tm.tm_min, tm.tm_sec);
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    putchar('\n');
    fflush(stdout);
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---------- SHA-256 (discovery fingerprint only) ---------- */

static const uint32_t K256[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};

#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256_block(uint32_t h[8], const uint8_t *p) {
    uint32_t w[64], a, b, c, d, e, f, g, hh;
    for (int i = 0; i < 16; i++)
        w[i] = (uint32_t)p[i*4] << 24 | p[i*4+1] << 16 | p[i*4+2] << 8 | p[i*4+3];
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i-15], 7) ^ ROR(w[i-15], 18) ^ (w[i-15] >> 3);
        uint32_t s1 = ROR(w[i-2], 17) ^ ROR(w[i-2], 19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    a = h[0]; b = h[1]; c = h[2]; d = h[3]; e = h[4]; f = h[5]; g = h[6]; hh = h[7];
    for (int i = 0; i < 64; i++) {
        uint32_t t1 = hh + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + K256[i] + w[i];
        uint32_t t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
}

static void sha256(const uint8_t *msg, size_t len, uint8_t out[32]) {
    uint32_t h[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint8_t blk[64];
    size_t i = 0;
    for (; i + 64 <= len; i += 64) sha256_block(h, msg + i);
    size_t rem = len - i;
    memset(blk, 0, 64);
    memcpy(blk, msg + i, rem);
    blk[rem] = 0x80;
    if (rem >= 56) { sha256_block(h, blk); memset(blk, 0, 64); }
    uint64_t bits = (uint64_t)len * 8;
    for (int j = 0; j < 8; j++) blk[63 - j] = (uint8_t)(bits >> (8 * j));
    sha256_block(h, blk);
    for (int j = 0; j < 8; j++) {
        out[j*4] = h[j] >> 24; out[j*4+1] = h[j] >> 16; out[j*4+2] = h[j] >> 8; out[j*4+3] = h[j];
    }
}

/* ---------- uinput devices ---------- */

/* Phone button bit -> Linux key code (Android's Xbox 360 layout). */
static const int BTN_MAP[12] = {
    BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
    BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR, BTN_TL2, BTN_TR2};

/* dpad 0..8 (clockwise from Up) -> hat x/y. */
static const int HAT_X[9] = {0, 0, 1, 1, 1, 0, -1, -1, -1};
static const int HAT_Y[9] = {0, -1, -1, 0, 1, 1, 1, 0, -1};

static void emit(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type; ev.code = code; ev.value = value;
    if (write(fd, &ev, sizeof ev) < 0) logf_("uinput write: %s", strerror(errno));
}

static void set_abs(struct uinput_user_dev *d, int code, int min, int max, int flat) {
    d->absmin[code] = min; d->absmax[code] = max; d->absflat[code] = flat;
}

static int make_pad(int player) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { logf_("open /dev/uinput: %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    for (int i = 0; i < 12; i++) ioctl(fd, UI_SET_KEYBIT, BTN_MAP[i]);
    ioctl(fd, UI_SET_KEYBIT, BTN_MODE);
    int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y};
    for (unsigned i = 0; i < sizeof axes / sizeof *axes; i++) ioctl(fd, UI_SET_ABSBIT, axes[i]);

    struct uinput_user_dev d;
    memset(&d, 0, sizeof d);
    snprintf(d.name, UINPUT_MAX_NAME_SIZE, "PocketPad %d (Xbox 360 Controller)", player + 1);
    d.id.bustype = BUS_USB; d.id.vendor = 0x045e; d.id.product = 0x028e; d.id.version = 0x0110 + player;
    set_abs(&d, ABS_X, -32768, 32767, 128);
    set_abs(&d, ABS_Y, -32768, 32767, 128);
    set_abs(&d, ABS_RX, -32768, 32767, 128);
    set_abs(&d, ABS_RY, -32768, 32767, 128);
    set_abs(&d, ABS_Z, 0, 255, 0);
    set_abs(&d, ABS_RZ, 0, 255, 0);
    set_abs(&d, ABS_HAT0X, -1, 1, 0);
    set_abs(&d, ABS_HAT0Y, -1, 1, 0);
    if (write(fd, &d, sizeof d) != sizeof d || ioctl(fd, UI_DEV_CREATE) < 0) {
        logf_("create pad: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static int make_mouse(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    struct uinput_user_dev d;
    memset(&d, 0, sizeof d);
    snprintf(d.name, UINPUT_MAX_NAME_SIZE, "PocketPad Mouse");
    d.id.bustype = BUS_USB; d.id.vendor = 0x1d6b; d.id.product = 0x0104; d.id.version = 1;
    if (write(fd, &d, sizeof d) != sizeof d || ioctl(fd, UI_DEV_CREATE) < 0) {
        logf_("create mouse: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void destroy_dev(int fd) {
    if (fd < 0) return;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

/* ---------- players ---------- */

typedef struct {
    int tcp;              /* -1 = slot free */
    int pad;              /* uinput fd */
    char name[64];
    char buf[1024];
    int buflen;
    int have_seq;
    uint16_t last_seq;
    long long last_state_ms;
    /* Last values written, so only changes go out. */
    uint16_t buttons;
    uint8_t dpad;
    int16_t lx, ly, rx, ry;
    uint8_t mbuttons;
    int lat_ms;           /* last RTT the phone reported, -1 = none yet */
    int ready;            /* handshake done */
} Player;

static Player players[MAX_PLAYERS];
static int mouse_fd = -1;
static char token[64];
static unsigned long inputs; /* presses this session, for the TV app */
static long long started_ms;

static void pad_apply(Player *p, uint16_t buttons, uint8_t dpad,
                      int16_t lx, int16_t ly, int16_t rx, int16_t ry) {
    int fd = p->pad;
    if (fd < 0) return;
    int changed = 0;
    for (int i = 0; i < 12; i++) {
        int now = (buttons >> i) & 1, was = (p->buttons >> i) & 1;
        if (now != was) { emit(fd, EV_KEY, BTN_MAP[i], now); changed = 1; inputs += now; }
    }
    /* Triggers are digital in v1: full travel on the analog axes too. */
    if ((buttons ^ p->buttons) & (1 << 10)) emit(fd, EV_ABS, ABS_Z, (buttons >> 10 & 1) ? 255 : 0);
    if ((buttons ^ p->buttons) & (1 << 11)) emit(fd, EV_ABS, ABS_RZ, (buttons >> 11 & 1) ? 255 : 0);
    if (dpad > 8) dpad = 0;
    if (dpad != p->dpad) {
        if (HAT_X[dpad] != HAT_X[p->dpad]) emit(fd, EV_ABS, ABS_HAT0X, HAT_X[dpad]);
        if (HAT_Y[dpad] != HAT_Y[p->dpad]) emit(fd, EV_ABS, ABS_HAT0Y, HAT_Y[dpad]);
        changed = 1;
        if (dpad) inputs++;
    }
    /* Protocol Y is up-positive; Linux/Android Y is down-positive. */
    int ny = ly == -32768 ? 32767 : -ly, nry = ry == -32768 ? 32767 : -ry;
    int oy = p->ly == -32768 ? 32767 : -p->ly, ory = p->ry == -32768 ? 32767 : -p->ry;
    if (lx != p->lx) { emit(fd, EV_ABS, ABS_X, lx); changed = 1; }
    if (ny != oy) { emit(fd, EV_ABS, ABS_Y, ny); changed = 1; }
    if (rx != p->rx) { emit(fd, EV_ABS, ABS_RX, rx); changed = 1; }
    if (nry != ory) { emit(fd, EV_ABS, ABS_RY, nry); changed = 1; }
    if (changed || ((buttons ^ p->buttons) & 0xC00)) emit(fd, EV_SYN, SYN_REPORT, 0);
    p->buttons = buttons; p->dpad = dpad;
    p->lx = lx; p->ly = ly; p->rx = rx; p->ry = ry;
}

static void mouse_apply(Player *p, uint8_t buttons, int16_t dx, int16_t dy, int8_t wheel) {
    if (mouse_fd < 0) mouse_fd = make_mouse();
    if (mouse_fd < 0) return;
    static const int MB[3] = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE};
    int changed = 0;
    for (int i = 0; i < 3; i++) {
        int now = (buttons >> i) & 1, was = (p->mbuttons >> i) & 1;
        if (now != was) { emit(mouse_fd, EV_KEY, MB[i], now); changed = 1; inputs += now; }
    }
    if (dx) { emit(mouse_fd, EV_REL, REL_X, dx); changed = 1; }
    if (dy) { emit(mouse_fd, EV_REL, REL_Y, dy); changed = 1; }
    if (wheel) { emit(mouse_fd, EV_REL, REL_WHEEL, wheel); changed = 1; }
    if (changed) emit(mouse_fd, EV_SYN, SYN_REPORT, 0);
    p->mbuttons = buttons;
}

static void release_all(Player *p) {
    pad_apply(p, 0, 0, 0, 0, 0, 0);
    if (p->mbuttons) mouse_apply(p, 0, 0, 0, 0);
}

static void drop_player(int i, const char *why) {
    Player *p = &players[i];
    if (p->tcp < 0) return;
    logf_("player %d (%s) disconnected: %s", i + 1, p->name, why);
    release_all(p);
    destroy_dev(p->pad);
    close(p->tcp);
    memset(p, 0, sizeof *p);
    p->tcp = -1; p->pad = -1;
}

/* ---------- control channel (JSON lines) ---------- */

/* Copy the string value of "key" into out. Enough JSON for our flat messages. */
static int json_str(const char *line, const char *key, char *out, size_t n) {
    char pat[32];
    snprintf(pat, sizeof pat, "\"%s\"", key);
    const char *s = strstr(line, pat);
    if (!s) return 0;
    s = strchr(s + strlen(pat), ':');
    if (!s) return 0;
    while (*++s == ' ');
    if (*s != '"') return 0;
    size_t k = 0;
    for (s++; *s && *s != '"' && k + 1 < n; s++) {
        if (*s == '\\' && s[1]) s++;
        out[k++] = *s;
    }
    out[k] = 0;
    return 1;
}

/* Copy the raw number literal of "key" (kept as text so it echoes exactly). */
static int json_num(const char *line, const char *key, char *out, size_t n) {
    char pat[32];
    snprintf(pat, sizeof pat, "\"%s\"", key);
    const char *s = strstr(line, pat);
    if (!s) return 0;
    s = strchr(s + strlen(pat), ':');
    if (!s) return 0;
    while (*++s == ' ');
    size_t k = 0;
    while ((*s == '-' || (*s >= '0' && *s <= '9')) && k + 1 < n) out[k++] = *s++;
    out[k] = 0;
    return k > 0;
}

static void send_line(int fd, const char *s) {
    size_t len = strlen(s);
    if (send(fd, s, len, MSG_NOSIGNAL) != (ssize_t)len) { /* reader will see the close */ }
}

/* Returns 0 to keep the connection, -1 to drop it. */
static int handle_line(int i, char *line) {
    Player *p = &players[i];
    char t[16] = "", buf[256];
    json_str(line, "t", t, sizeof t);
    if (!strcmp(t, "hello")) {
        char v[8] = "", tok[64] = "";
        json_num(line, "v", v, sizeof v);
        json_str(line, "token", tok, sizeof tok);
        json_str(line, "name", p->name, sizeof p->name);
        if (atoi(v) != 1) {
            send_line(p->tcp, "{\"t\":\"reject\",\"reason\":\"version\"}\n");
            return -1;
        }
        if (strcmp(tok, token)) {
            logf_("rejected %s: wrong pairing code", p->name);
            send_line(p->tcp, "{\"t\":\"reject\",\"reason\":\"token\"}\n");
            return -1;
        }
        if (p->pad < 0) p->pad = make_pad(i);
        p->have_seq = 0;
        p->ready = 1;
        p->lat_ms = -1;
        p->last_state_ms = now_ms();
        snprintf(buf, sizeof buf, "{\"t\":\"welcome\",\"v\":1,\"udp\":%d,\"player\":%d}\n", UDP_PORT, i);
        send_line(p->tcp, buf);
        logf_("player %d connected: %s", i + 1, p->name);
    } else if (!strcmp(t, "ping")) {
        char id[24] = "0", ts[24] = "0";
        json_num(line, "id", id, sizeof id);
        json_num(line, "ts", ts, sizeof ts);
        snprintf(buf, sizeof buf, "{\"t\":\"pong\",\"id\":%s,\"ts\":%s}\n", id, ts);
        send_line(p->tcp, buf);
    } else if (!strcmp(t, "lat")) {
        char ms[16] = "";
        if (json_num(line, "ms", ms, sizeof ms)) p->lat_ms = atoi(ms);
    } else if (!strcmp(t, "bye")) {
        return -1;
    }
    return 0;
}

/* ---------- UDP: state, mouse, discovery ---------- */

static uint8_t fingerprint[4];

static int seq_is_new(Player *p, uint16_t seq) {
    if (p->have_seq && (int16_t)(seq - p->last_seq) <= 0) return 0;
    p->have_seq = 1;
    p->last_seq = seq;
    return 1;
}

static void handle_udp(int fd) {
    uint8_t b[64];
    struct sockaddr_in from;
    socklen_t fl = sizeof from;
    ssize_t n = recvfrom(fd, b, sizeof b, 0, (struct sockaddr *)&from, &fl);
    if (n < 2 || b[1] != 1) return;
    if (b[0] == 0x44 && n >= 8) { /* 'D' discovery */
        if (b[2] == 0 && !memcmp(b + 4, fingerprint, 4)) {
            uint8_t r[8] = {0x44, 1, 1, 0, fingerprint[0], fingerprint[1], fingerprint[2], fingerprint[3]};
            sendto(fd, r, 8, 0, (struct sockaddr *)&from, fl);
            logf_("discovery reply to %s", inet_ntoa(from.sin_addr));
        }
        return;
    }
    if (b[0] == 0x50 && n >= 16) { /* 'P' pad state */
        int pl = b[7];
        if (pl >= MAX_PLAYERS || players[pl].tcp < 0) return;
        Player *p = &players[pl];
        if (!seq_is_new(p, b[2] | b[3] << 8)) return;
        p->last_state_ms = now_ms();
        if (p->mbuttons) mouse_apply(p, 0, 0, 0, 0);
        pad_apply(p, b[4] | b[5] << 8, b[6],
                  (int16_t)(b[8] | b[9] << 8), (int16_t)(b[10] | b[11] << 8),
                  (int16_t)(b[12] | b[13] << 8), (int16_t)(b[14] | b[15] << 8));
    } else if (b[0] == 0x4D && n >= 12) { /* 'M' mouse */
        int pl = b[5];
        if (pl >= MAX_PLAYERS || players[pl].tcp < 0) return;
        Player *p = &players[pl];
        if (!seq_is_new(p, b[2] | b[3] << 8)) return;
        p->last_state_ms = now_ms();
        mouse_apply(p, b[4], (int16_t)(b[6] | b[7] << 8), (int16_t)(b[8] | b[9] << 8), (int8_t)b[10]);
    }
}

/* ---------- status for the TV app ---------- */

/* One JSON line per connection, then close. Names are escaped minimally. */
static void serve_status(int lst) {
    int c = accept(lst, NULL, NULL);
    if (c < 0) return;
    char out[1024];
    int n = snprintf(out, sizeof out,
        "{\"t\":\"status\",\"v\":1,\"token\":\"%s\",\"tcp\":%d,\"inputs\":%lu,\"uptime\":%lld,\"players\":[",
        token, TCP_PORT, inputs, (now_ms() - started_ms) / 1000);
    int first = 1;
    for (int i = 0; i < MAX_PLAYERS; i++) {
        Player *p = &players[i];
        if (p->tcp < 0 || !p->ready) continue;
        char nm[64];
        size_t k = 0;
        for (const char *s = p->name; *s && k + 1 < sizeof nm; s++)
            if (*s != '"' && *s != '\\' && (unsigned char)*s >= 0x20) nm[k++] = *s;
        nm[k] = 0;
        n += snprintf(out + n, sizeof out - n, "%s{\"slot\":%d,\"name\":\"%s\",\"ms\":%d}",
                      first ? "" : ",", i, nm, p->lat_ms);
        first = 0;
    }
    n += snprintf(out + n, sizeof out - n, "]}\n");
    send(c, out, n, MSG_NOSIGNAL);
    close(c);
}

/* ---------- main ---------- */

static void load_token(const char *arg) {
    if (arg && *arg) {
        snprintf(token, sizeof token, "%s", arg);
        FILE *f = fopen(TOKEN_FILE, "w");
        if (f) { fprintf(f, "%s\n", token); fclose(f); }
        return;
    }
    FILE *f = fopen(TOKEN_FILE, "r");
    if (f) {
        if (fgets(token, sizeof token, f)) token[strcspn(token, "\r\n ")] = 0;
        fclose(f);
        if (*token) return;
    }
    /* Same alphabet idea as the PC app: no 0/O/1/I to misread. */
    static const char A[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    uint8_t r[8] = {0};
    int u = open("/dev/urandom", O_RDONLY);
    if (u >= 0) { if (read(u, r, 8) != 8) {} close(u); }
    for (int i = 0; i < 8; i++) token[i] = A[r[i] % 32];
    token[8] = 0;
    f = fopen(TOKEN_FILE, "w");
    if (f) { fprintf(f, "%s\n", token); fclose(f); }
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGHUP, SIG_IGN);
    load_token(argc > 1 ? argv[1] : NULL);
    uint8_t h[32];
    sha256((const uint8_t *)token, strlen(token), h);
    memcpy(fingerprint, h, 4);
    for (int i = 0; i < MAX_PLAYERS; i++) { players[i].tcp = -1; players[i].pad = -1; }

    int one = 1;
    int udp = socket(AF_INET, SOCK_DGRAM, 0);
    setsockopt(udp, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    setsockopt(udp, SOL_SOCKET, SO_BROADCAST, &one, sizeof one);
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET; a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons(UDP_PORT);
    if (bind(udp, (struct sockaddr *)&a, sizeof a) < 0) { logf_("bind udp: %s", strerror(errno)); return 1; }

    int lst = socket(AF_INET, SOCK_STREAM, 0);
    setsockopt(lst, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    a.sin_port = htons(TCP_PORT);
    if (bind(lst, (struct sockaddr *)&a, sizeof a) < 0 || listen(lst, 4) < 0) {
        logf_("bind tcp: %s", strerror(errno));
        return 1;
    }
    int st = socket(AF_INET, SOCK_STREAM, 0);
    setsockopt(st, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons(STATUS_PORT);
    if (bind(st, (struct sockaddr *)&a, sizeof a) < 0 || listen(st, 4) < 0) {
        logf_("bind status: %s", strerror(errno));
        return 1;
    }
    started_ms = now_ms();
    logf_("PocketPad TV ready — pairing code: %s (TCP %d, UDP %d)", token, TCP_PORT, UDP_PORT);

    for (;;) {
        struct pollfd pf[3 + MAX_PLAYERS];
        int map[3 + MAX_PLAYERS], n = 0;
        pf[n].fd = st; pf[n].events = POLLIN; map[n++] = -3;
        pf[n].fd = udp; pf[n].events = POLLIN; map[n++] = -1;
        pf[n].fd = lst; pf[n].events = POLLIN; map[n++] = -2;
        for (int i = 0; i < MAX_PLAYERS; i++)
            if (players[i].tcp >= 0) { pf[n].fd = players[i].tcp; pf[n].events = POLLIN; map[n++] = i; }
        if (poll(pf, n, 500) < 0 && errno != EINTR) { logf_("poll: %s", strerror(errno)); return 1; }

        for (int k = 0; k < n; k++) {
            if (!pf[k].revents) continue;
            if (map[k] == -3) {
                serve_status(st);
            } else if (map[k] == -1) {
                handle_udp(udp);
            } else if (map[k] == -2) {
                int c = accept(lst, NULL, NULL);
                if (c < 0) continue;
                setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
                int slot = -1;
                for (int i = 0; i < MAX_PLAYERS; i++) if (players[i].tcp < 0) { slot = i; break; }
                if (slot < 0) {
                    send_line(c, "{\"t\":\"reject\",\"reason\":\"busy\"}\n");
                    close(c);
                    continue;
                }
                memset(&players[slot], 0, sizeof players[slot]);
                players[slot].tcp = c; players[slot].pad = -1;
                strcpy(players[slot].name, "?");
            } else {
                int i = map[k];
                Player *p = &players[i];
                ssize_t r = recv(p->tcp, p->buf + p->buflen, sizeof p->buf - 1 - p->buflen, 0);
                if (r <= 0) { drop_player(i, r == 0 ? "closed" : strerror(errno)); continue; }
                p->buflen += r;
                p->buf[p->buflen] = 0;
                char *start = p->buf, *nl;
                int drop = 0;
                while (!drop && (nl = strchr(start, '\n'))) {
                    *nl = 0;
                    if (handle_line(i, start) < 0) drop = 1;
                    start = nl + 1;
                }
                if (drop) { drop_player(i, "bye/rejected"); continue; }
                p->buflen -= start - p->buf;
                memmove(p->buf, start, p->buflen);
                if (p->buflen >= (int)sizeof p->buf - 1) drop_player(i, "line too long");
            }
        }

        /* 5 s without state packets: release that player's controls. */
        long long t = now_ms();
        for (int i = 0; i < MAX_PLAYERS; i++) {
            Player *p = &players[i];
            if (p->tcp >= 0 && p->pad >= 0 && t - p->last_state_ms > STATE_TIMEOUT_MS) {
                release_all(p);
                p->last_state_ms = t;
            }
        }
    }
}
