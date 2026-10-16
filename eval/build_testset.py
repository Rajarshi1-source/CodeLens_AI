"""Source of truth for eval/testset.jsonl.

Each case is written here as readable code with the planted bug marked, and this script renders the unified
diff (hunk headers and line counts computed, so they are always consistent) and writes testset.jsonl.
Re-run after editing:  python eval/build_testset.py

Case fields (see eval_types.TestCase):
  id, category, language, diff
  expected_findings  [{line, min_severity, file?}]   planted bugs (new-file line numbers)
  must_not_flag      [{line, file?}]                  lines a good reviewer must leave alone
  context_files      {path: content}                  repository files available to G1 retrieval
  expected_risk      HIGH | MEDIUM | LOW              the PR risk band the scorer should assign
  tags               e.g. ["cross-file"]               cross-file cases need context to be caught
"""

from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "testset.jsonl")


def hunk(path: str, start: int, lines: list[str], old_start: int | None = None) -> str:
    """Render one-hunk diff for `path`. `lines` carry their prefix: ' ' context, '+' added, '-' removed."""
    old_count = sum(1 for ln in lines if ln[:1] in (" ", "-"))
    new_count = sum(1 for ln in lines if ln[:1] in (" ", "+"))
    o = start if old_start is None else old_start
    header = f"@@ -{o},{old_count} +{start},{new_count} @@"
    body = "\n".join(lines)
    return f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n{header}\n{body}\n"


def new_line_of(start: int, lines: list[str], marker: str) -> int:
    """New-file line number of the first added/context line containing `marker`."""
    n = start
    for ln in lines:
        if ln.startswith("-"):
            continue
        if marker in ln:
            return n
        n += 1
    raise ValueError(f"marker {marker!r} not found")


CASES: list[dict] = []


def case(id: str, category: str, language: str, path: str, start: int, lines: list[str], *,
         bugs: list[tuple[str, str]] = (), clean_lines: list[str] = (), context: dict | None = None,
         risk: str | None = None, tags: list[str] = (), old_start: int | None = None) -> None:
    CASES.append({
        "id": id,
        "category": category,
        "language": language,
        "diff": hunk(path, start, lines, old_start),
        "expected_findings": [
            {"line": new_line_of(start, lines, marker), "min_severity": sev, "file": path} for marker, sev in bugs
        ],
        "must_not_flag": [{"line": new_line_of(start, lines, marker), "file": path} for marker in clean_lines],
        "context_files": context or {},
        "expected_risk": risk,
        "tags": list(tags),
    })


# --- security: hard-coded credentials ---------------------------------------------------------------

case("sec-hardcoded-secret", "security", "java", "src/main/java/Config.java", 1, [
    " public class Config {",
    '+    String apiKey = "abcd1234secret";',
    "     int timeout = 30;",
    " }",
], bugs=[("apiKey", "CRITICAL")], risk="MEDIUM")

case("sec-openai-key", "security", "java", "src/main/java/Client.java", 10, [
    " public class Client {",
    '+    private final String key = "sk-abc123def456";',
    " }",
], bugs=[("sk-abc", "CRITICAL")], risk="MEDIUM")

case("sec-password-ts", "security", "typescript", "src/db/connect.ts", 4, [
    " export function connect() {",
    '+  const password = "hunter2hunter2";',
    "   return open({ host: 'db', user: 'app', password });",
    " }",
], bugs=[("hunter2hunter2", "CRITICAL")], risk="MEDIUM")

case("sec-secret-python", "security", "python", "app/settings.py", 12, [
    " DEBUG = False",
    '+AWS_SECRET = "wJalrXUtnFEMIK7MDENGbPxRfiCY"',
    " REGION = 'eu-west-1'",
], bugs=[("AWS_SECRET", "CRITICAL")], risk="MEDIUM")

case("sec-token-js", "security", "javascript", "scripts/publish.js", 3, [
    " const fetch = require('node-fetch');",
    '+var accessToken = "ghp_8f7e6d5c4b3a2f1e0d9c";',
    " module.exports = { fetch };",
], bugs=[("accessToken", "CRITICAL")], risk="MEDIUM")

case("sec-apikey-kotlin", "security", "kotlin", "app/src/main/kotlin/Maps.kt", 7, [
    " object Maps {",
    '+    val apiKey = "AIzaSyD-1234567890abcdef"',
    "     fun client() = MapsClient(apiKey)",
    " }",
], bugs=[("val apiKey", "CRITICAL")], risk="MEDIUM")

# --- security: SQL built by concatenation -----------------------------------------------------------

case("sec-sql-injection", "sql", "java", "src/main/java/UserDao.java", 5, [
    " public class UserDao {",
    '+    String q = "SELECT * FROM users WHERE id = " + id;',
    " }",
], bugs=[("SELECT", "CRITICAL")], risk="MEDIUM")

case("sql-concat-python", "sql", "python", "app/orders/repo.py", 20, [
    " def load(cursor, order_id):",
    '+    cursor.execute("SELECT * FROM orders WHERE id = " + order_id)',
    "     return cursor.fetchone()",
], bugs=[("cursor.execute", "CRITICAL")], risk="MEDIUM")

case("sql-concat-update", "sql", "java", "src/main/java/ProfileDao.java", 31, [
    "     void rename(long id, String name) throws SQLException {",
    "+        String q = \"UPDATE users SET name = '\" + name + \"' WHERE id = \" + id;",
    "         statement.executeUpdate(q);",
    "     }",
], bugs=[("UPDATE users", "CRITICAL")], risk="MEDIUM")

# --- null dereference -------------------------------------------------------------------------------

case("null-deref-chain", "null", "java", "src/main/java/ShippingService.java", 14, [
    "     String cityOf(User user) {",
    "+        return user.getAddress().getCity();",
    "     }",
], bugs=[("getAddress", "WARNING")], risk="MEDIUM")

case("null-deref-map", "null", "java", "src/main/java/SessionCache.java", 22, [
    "     int sizeOf(String key) {",
    "+        int n = cache.get(key).size();",
    "         return n;",
    "     }",
], bugs=[("cache.get", "WARNING")], risk="MEDIUM")

case("null-deref-ts", "null", "typescript", "src/users/view.ts", 9, [
    " export function title(repo: Repo, id: string) {",
    "+  return repo.getUser(id).displayName();",
    " }",
], bugs=[("getUser", "WARNING")], risk="MEDIUM")

# --- concurrency ------------------------------------------------------------------------------------

case("conc-counter", "concurrency", "java", "src/main/java/Metrics.java", 6, [
    "     public void record() {",
    "+        count++;",
    "     }",
], bugs=[("count++", "WARNING")], risk="LOW")

case("conc-hits", "concurrency", "java", "src/main/java/HitCounter.java", 18, [
    "     void onRequest(Request r) {",
    "         log(r);",
    "+        hits++;",
    "     }",
], bugs=[("hits++", "WARNING")], risk="LOW")

case("conc-this-field", "concurrency", "java", "src/main/java/RateWindow.java", 40, [
    "     public void tick() {",
    "+        this.requests++;",
    "     }",
], bugs=[("requests++", "WARNING")], risk="LOW")

# --- off-by-one -------------------------------------------------------------------------------------

case("obo-loop", "off-by-one", "java", "src/main/java/Totals.java", 9, [
    "     int sum(int[] items) {",
    "         int s = 0;",
    "+        for (int i = 0; i <= items.length; i++) { s += items[i]; }",
    "         return s;",
    "     }",
], bugs=[("i <= items.length", "WARNING")], risk="LOW")

case("obo-index", "off-by-one", "java", "src/main/java/Ring.java", 27, [
    "     int last(int[] arr) {",
    "+        return arr[arr.length + 1];",
    "     }",
], bugs=[("arr.length + 1", "WARNING")], risk="LOW")

# --- I/O ----------------------------------------------------------------------------------------------

case("bug-io-no-handling", "io", "java", "src/main/java/FileLoader.java", 3, [
    " public class FileLoader {",
    "+    String content = Files.readString(path);",
    " }",
], bugs=[("readString", "WARNING")], risk="LOW")

case("io-read-bytes", "io", "java", "src/main/java/Uploader.java", 12, [
    "     byte[] load(Path path) {",
    "+        byte[] data = Files.readAllBytes(path);",
    "         return data;",
    "     }",
], bugs=[("readAllBytes", "WARNING")], risk="LOW")

# --- cross-file (G1): only visible with repository context --------------------------------------------

PRICE_CALC_JAVA = """package shop;

import java.util.List;

public class PriceCalculator {

    public long calculateTotal(List<Long> items) {
        long sum = 0;
        for (long p : items) {
            sum += p;
        }
        return sum;
    }
}
"""
CHECKOUT_JAVA = """package shop;

import java.util.List;

public class CheckoutController {
    private final PriceCalculator calculator = new PriceCalculator();

    public long checkout(List<Long> items) {
        return calculator.calculateTotal(items);
    }
}
"""
case("xf-arity-java", "cross-file", "java", "src/main/java/shop/PriceCalculator.java", 7, [
    "-    public long calculateTotal(List<Long> items) {",
    "+    public long calculateTotal(List<Long> items, String coupon) {",
    "         long sum = 0;",
    "         for (long p : items) {",
    "             sum += p;",
    "         }",
    "+        if (coupon != null) { sum = sum * 9 / 10; }",
    "         return sum;",
    "     }",
], bugs=[("String coupon", "CRITICAL")], tags=["cross-file"], risk="MEDIUM", context={
    "src/main/java/shop/PriceCalculator.java": PRICE_CALC_JAVA,
    "src/main/java/shop/CheckoutController.java": CHECKOUT_JAVA,
})

LEGACY_JAVA = """package billing;

public class TaxService {

    public long legacyTax(long amount) {
        return amount / 10;
    }

    public long tax(long amount, String region) {
        return "EU".equals(region) ? amount / 5 : amount / 10;
    }
}
"""
INVOICE_JAVA = """package billing;

public class InvoiceService {
    private final TaxService taxes = new TaxService();

    public long totalWithTax(long amount) {
        return amount + taxes.legacyTax(amount);
    }
}
"""
case("xf-removed-java", "cross-file", "java", "src/main/java/billing/TaxService.java", 5, [
    "-    public long legacyTax(long amount) {",
    "-        return amount / 10;",
    "-    }",
    "-",
    "     public long tax(long amount, String region) {",
    "         return \"EU\".equals(region) ? amount / 5 : amount / 10;",
    "     }",
], bugs=[("public long tax(", "CRITICAL")], tags=["cross-file"], risk="MEDIUM", context={
    "src/main/java/billing/TaxService.java": LEGACY_JAVA,
    "src/main/java/billing/InvoiceService.java": INVOICE_JAVA,
})

REPORTS_JAVA = """package reports;

public class ReportStore {

    public Report findReport(String id) {
        return store.get(id);
    }
}
"""
REPORT_CTRL_JAVA = """package reports;

public class ReportController {
    private final ReportStore reports = new ReportStore();

    public String title(String id) {
        return reports.findReport(id).title();
    }
}
"""
case("xf-null-java", "cross-file", "java", "src/main/java/reports/ReportStore.java", 5, [
    "     public Report findReport(String id) {",
    "+        if (id == null || id.isBlank()) {",
    "+            return null;",
    "+        }",
    "         return store.get(id);",
    "     }",
], bugs=[("return null;", "WARNING")], tags=["cross-file"], risk="LOW", context={
    "src/main/java/reports/ReportStore.java": REPORTS_JAVA,
    "src/main/java/reports/ReportController.java": REPORT_CTRL_JAVA,
})

MAILER_PY = '''def send_email(to, subject):
    return smtp().send(to, subject)
'''
SIGNUP_PY = '''from app.mailer import send_email


def on_signup(user):
    send_email(user.email, "Welcome")
'''
case("xf-arity-python", "cross-file", "python", "app/mailer.py", 1, [
    "-def send_email(to, subject):",
    "-    return smtp().send(to, subject)",
    "+def send_email(to, subject, body):",
    "+    return smtp().send(to, subject, body)",
], bugs=[("def send_email(to, subject, body)", "CRITICAL")], tags=["cross-file"], risk="MEDIUM", context={
    "app/mailer.py": MAILER_PY,
    "app/signup.py": SIGNUP_PY,
})

FORMAT_TS = """export function formatPrice(amount: number): string {
  return amount.toFixed(2);
}
"""
CART_TS = """import { formatPrice } from './format';

export function cartLabel(total: number): string {
  return `Total: ${formatPrice(total)}`;
}
"""
case("xf-arity-ts", "cross-file", "typescript", "src/format.ts", 1, [
    "-export function formatPrice(amount: number): string {",
    "-  return amount.toFixed(2);",
    "+export function formatPrice(amount: number, currency: string): string {",
    "+  return `${currency} ${amount.toFixed(2)}`;",
    " }",
], bugs=[("currency: string", "CRITICAL")], tags=["cross-file"], risk="MEDIUM", context={
    "src/format.ts": FORMAT_TS,
    "src/cart.ts": CART_TS,
})

USERS_TS = """export function findUser(id: string): User {
  return db.users.get(id);
}
"""
PROFILE_TS = """import { findUser } from './users';

export function profileName(id: string): string {
  return findUser(id).name.trim();
}
"""
case("xf-null-ts", "cross-file", "typescript", "src/users.ts", 1, [
    " export function findUser(id: string): User {",
    "+  if (!id) {",
    "+    return null;",
    "+  }",
    "   return db.users.get(id);",
    " }",
], bugs=[("return null;", "WARNING")], tags=["cross-file"], risk="LOW", context={
    "src/users.ts": USERS_TS,
    "src/profile.ts": PROFILE_TS,
})

# --- clean controls: the reviewer must stay quiet -------------------------------------------------------

case("clean-simple-add", "clean", "java", "src/main/java/Math.java", 1, [
    " public class Math {",
    "+    int total = first + second;",
    "     int base = 10;",
    " }",
], clean_lines=["int total"], risk="LOW")

case("clean-logging", "clean", "java", "src/main/java/Service.java", 8, [
    " public class Service {",
    '+    logger.info("processing complete");',
    " }",
], clean_lines=["logger.info"], risk="LOW")

case("clean-env-secret", "clean", "java", "src/main/java/Keys.java", 3, [
    " public class Keys {",
    '+    String apiKey = System.getenv("API_KEY");',
    " }",
], clean_lines=["System.getenv"], risk="LOW")

case("clean-prepared-statement", "clean", "java", "src/main/java/AccountDao.java", 16, [
    "     Account load(long id) throws SQLException {",
    '+        PreparedStatement ps = connection.prepareStatement("SELECT * FROM accounts WHERE id = ?");',
    "+        ps.setLong(1, id);",
    "         return map(ps.executeQuery());",
    "     }",
], clean_lines=["prepareStatement", "setLong"], risk="LOW")

case("clean-atomic-counter", "clean", "java", "src/main/java/Counter.java", 9, [
    "     void inc() {",
    "+        counter.incrementAndGet();",
    "     }",
], clean_lines=["incrementAndGet"], risk="LOW")

# Cross-file control: the signature changed AND the caller was updated in the repo — no finding.
CHECKOUT_UPDATED_JAVA = CHECKOUT_JAVA.replace("calculateTotal(items)", "calculateTotal(items, null)")
case("clean-xf-callers-updated", "clean", "java", "src/main/java/shop/PriceCalculator.java", 7, [
    "-    public long calculateTotal(List<Long> items) {",
    "+    public long calculateTotal(List<Long> items, String coupon) {",
    "         long sum = 0;",
], clean_lines=["String coupon"], tags=["cross-file"], risk="LOW", context={
    "src/main/java/shop/PriceCalculator.java": PRICE_CALC_JAVA,
    "src/main/java/shop/CheckoutController.java": CHECKOUT_UPDATED_JAVA,
})


def main() -> None:
    ids = [c["id"] for c in CASES]
    assert len(ids) == len(set(ids)), "duplicate case ids"
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        for c in CASES:
            fh.write(json.dumps(c, ensure_ascii=False) + "\n")
    xf = sum(1 for c in CASES if "cross-file" in c["tags"] and c["expected_findings"])
    clean = sum(1 for c in CASES if not c["expected_findings"])
    print(f"wrote {len(CASES)} cases ({xf} cross-file, {clean} clean) -> {OUT}")


if __name__ == "__main__":
    main()
