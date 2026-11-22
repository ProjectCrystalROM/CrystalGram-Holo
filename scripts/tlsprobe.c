/*
 * tlsprobe — on-device TLS validation for CrystalGram Holo's wolfSSL build.
 *
 * Mirrors what TDLib's tdnet/td/net/SslCtx.cpp + SslStream.cpp do:
 *   SSL_CTX_new(TLS_client_method())
 *   SSL_CTX_set_min_proto_version(TLS1_VERSION)
 *   SSL_CTX_set_cipher_list("DEFAULT")
 *   X509_STORE built from the device's CA bundle, handed to SSL_CTX_set_cert_store
 *   X509_VERIFY_PARAM_set1_host() for hostname checking
 *   SSL_CTX_set_verify(SSL_VERIFY_PEER, ...)
 *
 * Positive cases must connect AND verify; negative cases must FAIL verification.
 * Exit code 0 only if every expectation matched.
 */
#include <wolfssl/openssl/ssl.h>
#include <wolfssl/openssl/err.h>
#include <wolfssl/openssl/x509.h>
#include <wolfssl/openssl/x509_vfy.h>

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

static int failures = 0;

static const char *CA_BUNDLES[] = {
    "/system/etc/security/cacerts.bks",
    "/system/etc/security/cacerts",
    "/etc/security/cacerts",
    "/system/etc/ca-certificates.crt",
    NULL
};

/* Connect, run the handshake. Returns 1 on a verified handshake, 0 otherwise. */
static int try_host(const char *host, int port, int verify_host, char *err, size_t errlen) {
  err[0] = 0;
  struct hostent *he = gethostbyname(host);
  if (he == NULL) {
    snprintf(err, errlen, "DNS failure");
    return 0;
  }
  int fd = socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) {
    snprintf(err, errlen, "socket() failed");
    return 0;
  }
  struct timeval tv = {20, 0};
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
  setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
  struct sockaddr_in sa;
  memset(&sa, 0, sizeof sa);
  sa.sin_family = AF_INET;
  sa.sin_port = htons((unsigned short)port);
  memcpy(&sa.sin_addr, he->h_addr, sizeof sa.sin_addr);
  if (connect(fd, (struct sockaddr *)&sa, sizeof sa) != 0) {
    snprintf(err, errlen, "connect() failed");
    close(fd);
    return 0;
  }

  SSL_CTX *ctx = SSL_CTX_new(TLS_client_method());
  if (!ctx) {
    snprintf(err, errlen, "SSL_CTX_new failed");
    close(fd);
    return 0;
  }
  SSL_CTX_set_min_proto_version(ctx, TLS1_VERSION);
  SSL_CTX_set_cipher_list(ctx, "DEFAULT");
  SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, NULL);

  X509_STORE *store = X509_STORE_new();
  int loaded = 0;
  for (int i = 0; CA_BUNDLES[i]; i++) {
    if (access(CA_BUNDLES[i], R_OK) == 0) {
      if (X509_STORE_load_locations(store, CA_BUNDLES[i], NULL) == 1) loaded++;
    }
  }
  SSL_CTX_set_cert_store(ctx, store);

  SSL *ssl = SSL_new(ctx);
  if (!ssl) {
    snprintf(err, errlen, "SSL_new failed");
    SSL_CTX_free(ctx);
    close(fd);
    return 0;
  }
  if (verify_host) {
    X509_VERIFY_PARAM *param = SSL_get0_param(ssl);
    if (param && X509_VERIFY_PARAM_set1_host(param, host, 0) != 1) {
      snprintf(err, errlen, "set1_host unsupported");
    }
  }
  SSL_set_fd(ssl, fd);

  int ok = 0;
  if (SSL_connect(ssl) == 1) {
    long vr = SSL_get_verify_result(ssl);
    if (vr == X509_V_OK) {
      ok = 1;
    } else {
      snprintf(err, errlen, "verify_result=%ld", vr);
    }
  } else {
    long vr = SSL_get_verify_result(ssl);
    unsigned long e = ERR_peek_last_error();
    if (vr != X509_V_OK) {
      snprintf(err, errlen, "handshake failed, verify_result=%ld", vr);
    } else {
      snprintf(err, errlen, "handshake failed, err=0x%lx", e);
    }
  }

  SSL_free(ssl);
  SSL_CTX_free(ctx);
  close(fd);
  fprintf(stderr, "      [ca bundles loaded: %d]\n", loaded);
  return ok;
}

static void check(const char *label, const char *host, int expect_ok) {
  char err[256];
  int ok = try_host(host, 443, 1, err, sizeof err);
  int pass = (ok == expect_ok);
  if (!pass) failures++;
  printf("%-40s expect=%-7s got=%-7s %s%s%s\n", label, expect_ok ? "PASS" : "FAIL",
         ok ? "PASS" : "FAIL", pass ? "OK" : "MISMATCH",
         err[0] ? "  (" : "", err[0] ? err : "");
  if (err[0]) printf("%s\n", ")");
  fflush(stdout);
}

int main(void) {
  printf("tlsprobe: wolfSSL %s, OPENSSL_VERSION_NUMBER=0x%lx\n", wolfSSL_lib_version(),
         (unsigned long)OPENSSL_VERSION_NUMBER);
  fflush(stdout);

  /* Must verify: real Telegram endpoints. */
  check("telegram.org", "telegram.org", 1);
  check("core.telegram.org", "core.telegram.org", 1);
  check("api.telegram.org", "api.telegram.org", 1);
  check("google.com", "google.com", 1);

  /* Must NOT verify: badssl.com negative cases. */
  check("expired.badssl.com", "expired.badssl.com", 0);
  check("wrong.host.badssl.com", "wrong.host.badssl.com", 0);
  check("self-signed.badssl.com", "self-signed.badssl.com", 0);
  check("untrusted-root.badssl.com", "untrusted-root.badssl.com", 0);

  printf("\n%s (%d mismatch%s)\n", failures ? "RESULT: FAIL" : "RESULT: ALL OK", failures,
         failures == 1 ? "" : "es");
  return failures ? 1 : 0;
}
