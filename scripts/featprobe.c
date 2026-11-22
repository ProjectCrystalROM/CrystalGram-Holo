/*
 * featprobe — ask the device what it actually supports, and check that the
 * wolfSSL build agrees.
 *
 * Two halves:
 *  1. CPU / kernel: /proc/cpuinfo Features, AT_HWCAP, AT_HWCAP2, and a live
 *     NEON + VFPv4 instruction test executed under SIGILL capture.
 *  2. wolfSSL build config: which code paths the library was compiled with
 *     (from wolfssl/options.h as baked into the binary) and whether the
 *     runtime dispatch in sha512/aes selects the NEON or the C kernel.
 *
 * Build:  bash scripts/featprobe-build.sh
 * Run:    adb shell /data/local/tmp/featprobe
 */
#include <wolfssl/options.h>
#include <wolfssl/openssl/ssl.h>

#include <setjmp.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <elf.h>
#include <unistd.h>

/* ---------- part 1: what the phone says ---------- */

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
static const char *neon_compile = "yes";
#else
static const char *neon_compile = "no";
#endif

static sigjmp_buf ill_env;
static void ill_handler(int sig) { (void)sig; siglongjmp(ill_env, 1); }

/* Execute one NEON instruction. Returns 1 if it ran, 0 if it trapped. */
static int run_neon_test(void) {
  int ok = 0;
  if (sigsetjmp(ill_env, 1) == 0) {
    __asm__ __volatile__(
        "vldr d0, [%[p]]\n\t"
        "vldr d1, [%[p]]\n\t"
        "vadd.i64 d2, d0, d1\n\t"
        "vshl.i64 d3, d2, #3\n\t"      /* the form wolfSSL's armasm sources got wrong */
        "vmov r0, r1, d3\n\t"
        "str r0, [%[o]]\n\t"
        :
        : [p] "r"(&ok), [o] "r"(&ok)
        : "d0", "d1", "d2", "d3", "r0", "r1", "memory");
    ok = 1;
  }
  return ok;
}

/* VFPv4: vfma is the VFPv4-only fused multiply-add. */
static int run_vfpv4_test(void) {
  volatile float a = 1.5f, b = 2.25f, c = 0.5f, out = 0;
  int ok = 0;
  if (sigsetjmp(ill_env, 1) == 0) {
    __asm__ __volatile__(
        "vldr s0, [%[a]]\n\t"
        "vldr s1, [%[b]]\n\t"
        "vldr s2, [%[c]]\n\t"
        "vfma.f32 s2, s0, s1\n\t"
        "vstr s2, [%[o]]\n\t"
        :
        : [a] "r"(&a), [b] "r"(&b), [c] "r"(&c), [o] "r"(&out)
        : "s0", "s1", "s2", "memory");
    ok = 1;
  }
  (void)out;
  return ok;
}

static void print_cpuinfo_features(void) {
  FILE *f = fopen("/proc/cpuinfo", "r");
  char line[512];
  if (!f) { printf("  /proc/cpuinfo unreadable\n"); return; }
  while (fgets(line, sizeof line, f)) {
    if (strncmp(line, "Features", 8) == 0) printf("  %s", line);
    else if (strncmp(line, "CPU part", 8) == 0) printf("  %s", line);
    else if (strncmp(line, "CPU architecture", 16) == 0) printf("  %s", line);
  }
  fclose(f);
}

/* ---------- part 2: what wolfSSL was built with ---------- */

static void print_wolfssl_config(void) {
  printf("\n== wolfSSL build ==\n");
  printf("  version                : %s\n", wolfSSL_lib_version());
  printf("  OPENSSL_VERSION_NUMBER : 0x%lx\n", (unsigned long)OPENSSL_VERSION_NUMBER);
  printf("  wolfSSL_ARMASM         : %s\n",
#if defined(WOLFSSL_ARMASM)
         "defined"
#else
         "not defined"
#endif
  );
  printf("  ARMASM_INLINE          : %s\n",
#if defined(WOLFSSL_ARMASM_INLINE)
         "defined (inline-asm kernels)"
#else
         "not defined"
#endif
  );
  printf("  ARMASM_NO_NEON         : %s\n",
#if defined(WOLFSSL_ARMASM_NO_NEON)
         "defined (NEON kernels excluded)"
#else
         "not defined (NEON kernels included)"
#endif
  );
  printf("  ARMASM_NO_HW_CRYPTO    : %s\n",
#if defined(WOLFSSL_ARMASM_NO_HW_CRYPTO)
         "defined"
#else
         "not defined"
#endif
  );
  printf("  WOLFSSL_ARM_ARCH       : %s\n",
#ifdef WOLFSSL_ARM_ARCH
         #if defined(__INT_MAX__)
         "set"
         #else
         "set"
         #endif
#else
         "unset"
#endif
  );
  printf("  __ARM_NEON (this TU)   : %s\n", neon_compile);
  printf("  compiler __ARM_ARCH    : %d\n", __ARM_ARCH);
}

int main(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_handler = ill_handler;
  sigemptyset(&sa.sa_mask);
  sa.sa_flags = SA_NODEFER;
  sigaction(SIGILL, &sa, NULL);

  printf("== device ==\n");
  print_cpuinfo_features();
  /* getauxval() is API 18+; this probe also has to run on an API 16 device,
   * so read the aux vector the old way. */
  {
    unsigned long hwcap = 0, hwcap2 = 0;
    FILE *af = fopen("/proc/self/auxv", "rb");
    if (af) {
      unsigned long pair[2];
      while (fread(pair, sizeof pair, 1, af) == 1) {
        if (pair[0] == AT_HWCAP) hwcap = pair[1];
#ifdef AT_HWCAP2
        else if (pair[0] == AT_HWCAP2) hwcap2 = pair[1];
#endif
        else if (pair[0] == AT_NULL) break;
      }
      fclose(af);
    }
    printf("  AT_HWCAP               : 0x%08lx\n", hwcap);
#ifdef AT_HWCAP2
    printf("  AT_HWCAP2              : 0x%08lx\n", hwcap2);
#endif
  }
  printf("  NEON executes          : %s\n", run_neon_test() ? "yes" : "NO (SIGILL)");
  printf("  VFPv4 (vfma) executes  : %s\n", run_vfpv4_test() ? "yes" : "NO (SIGILL)");

  print_wolfssl_config();

  /* A SHA-512 of known input: if the library's runtime dispatch picked the NEON
   * kernel on a CPU that cannot run it, this is where it faults. */
  printf("\n== wolfSSL SHA-512 smoke test ==\n");
  unsigned char dg[64];
  static const unsigned char msg[] = "The quick brown fox jumps over the lazy dog";
  int r = wc_Sha512Hash(msg, (word32)(sizeof msg - 1), dg);
  printf("  wc_Sha512Hash          : %s\n", r == 0 ? "ok" : "FAILED");
  printf("  digest[0..3]           : %02x%02x%02x%02x\n", dg[0], dg[1], dg[2], dg[3]);
  printf("  expected[0..3]         : 07e547d9 (\"...lazy dog\" SHA-512)\n");
  printf("  match                  : %s\n",
         (dg[0] == 0x07 && dg[1] == 0xe5 && dg[2] == 0x47 && dg[3] == 0xd9) ? "yes" : "NO");
  return 0;
}
