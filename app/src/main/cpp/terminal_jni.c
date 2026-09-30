// Nakour 终端的 native 层。
//
// Java 没有伪终端的 API，所以必须自己 forkpty：
//   forkpty() 会开一对 master/slave，子进程把 slave 当成控制终端，
//   于是 isatty() 为真、ls 会带颜色、vim/top 这些全屏程序也认得出来。
//
// 只做四件事：开、改窗口大小、发信号、收尸。读写交给 Java 那边的流。
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "NakourTerm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/** 把一个 Java String 转成 C 字符串（调用方负责 free） */
static char *dup_string(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
    if (utf == NULL) return NULL;
    char *out = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, s, utf);
    return out;
}

static void free_array(char **arr, int n) {
    if (arr == NULL) return;
    for (int i = 0; i < n; i++) free(arr[i]);
    free(arr);
}

/**
 * 开一个终端会话。
 *
 * 返回 master fd（失败返回 -1），子进程 pid 写进 pidOut[0]。
 * cmd 是 argv（第一个是程序），env 是 "K=V" 数组。
 */
JNIEXPORT jint JNICALL
Java_lo_naui_term_TerminalNative_createSubprocess(
        JNIEnv *env, jclass clazz,
        jobjectArray cmdArray, jstring cwdStr, jobjectArray envArray,
        jintArray pidOut, jint rows, jint cols) {

    if (cmdArray == NULL) return -1;
    jsize cmdLen = (*env)->GetArrayLength(env, cmdArray);
    if (cmdLen <= 0) return -1;

    // ---- 先把所有字符串取出来。fork 之后不能再碰 JNI ----
    char **argv = calloc((size_t) cmdLen + 1, sizeof(char *));
    if (argv == NULL) return -1;
    for (jsize i = 0; i < cmdLen; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, cmdArray, i);
        argv[i] = dup_string(env, s);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[cmdLen] = NULL;

    char *cwd = dup_string(env, cwdStr);

    jsize envLen = envArray ? (*env)->GetArrayLength(env, envArray) : 0;
    char **envv = NULL;
    if (envLen > 0) {
        envv = calloc((size_t) envLen, sizeof(char *));
        for (jsize i = 0; i < envLen; i++) {
            jstring s = (jstring) (*env)->GetObjectArrayElement(env, envArray, i);
            envv[i] = dup_string(env, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int ptm = -1;
    pid_t pid = forkpty(&ptm, NULL, NULL, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        free_array(argv, cmdLen);
        free(cwd);
        free_array(envv, envLen);
        return -1;
    }

    if (pid == 0) {
        /* ---------------- 子进程 ---------------- */
        if (cwd != NULL) {
            if (chdir(cwd) != 0) chdir("/");
        }
        if (envv != NULL) {
            for (jsize i = 0; i < envLen; i++) putenv(envv[i]);
        }
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("TERMINFO", "/system/etc/terminfo", 1);
        setenv("LANG", "en_US.UTF-8", 1);
        setenv("LC_ALL", "en_US.UTF-8", 1);

        execvp(argv[0], argv);

        // 走到这儿说明 exec 失败
        char msg[256];
        snprintf(msg, sizeof(msg), "exec %s failed: %s\r\n", argv[0], strerror(errno));
        write(STDERR_FILENO, msg, strlen(msg));
        _exit(127);
    }

    /* ---------------- 父进程 ---------------- */
    free_array(argv, cmdLen);
    free(cwd);
    free_array(envv, envLen);

    jint pidValue = (jint) pid;
    (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &pidValue);
    return ptm;
}

/** 告诉内核终端有多大 —— vim/top 靠这个排版 */
JNIEXPORT void JNICALL
Java_lo_naui_term_TerminalNative_setPtyWindowSize(
        JNIEnv *env, jclass clazz,
        jint fd, jint rows, jint cols, jint cellWidth, jint cellHeight) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = (unsigned short) cellWidth;
    ws.ws_ypixel = (unsigned short) cellHeight;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/** 等进程结束，返回退出码（被信号杀掉返回 -1） */
JNIEXPORT jint JNICALL
Java_lo_naui_term_TerminalNative_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -1;
}

/** 给整个前台进程组发信号（负数 pid），Ctrl+C 就靠它 */
JNIEXPORT void JNICALL
Java_lo_naui_term_TerminalNative_sendSignalToGroup(
        JNIEnv *env, jclass clazz, jint pid, jint sig) {
    kill(-(pid_t) pid, sig);
}

/** 给单个进程发信号 */
JNIEXPORT void JNICALL
Java_lo_naui_term_TerminalNative_sendSignal(
        JNIEnv *env, jclass clazz, jint pid, jint sig) {
    kill((pid_t) pid, sig);
}

/** 这个 fd 是不是终端 */
JNIEXPORT jboolean JNICALL
Java_lo_naui_term_TerminalNative_isatty(JNIEnv *env, jclass clazz, jint fd) {
    return isatty(fd) ? JNI_TRUE : JNI_FALSE;
}
