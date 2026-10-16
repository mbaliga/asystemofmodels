#!/usr/bin/env bash
# Re-runs the iOS-section spikes (design session 2026-09-30). Linux x86_64, Swift 6.1 at /opt/swift. Ships nothing.
set -u; HERE="$(cd "$(dirname "$0")" && pwd)"; export PATH=/opt/swift/usr/bin:$PATH
echo "## S1: AsomDSSE (ES256/DSSE/strict SPKI/low-S) vs JCA transcript"; (cd "$HERE/AsomKitSpike" && swift test 2>&1 | grep -E 'sigValid|SIGNER-HIGH-S|Executed .* tests' | sort -u)
(cd "$HERE/AsomKitSpike" && swift test 2>&1 | grep sigValid) > "$HERE/swift.lines"; grep sigValid "$HERE/../../manifest-vectors/crosscheck.out" > "$HERE/java.lines"
diff "$HERE/swift.lines" "$HERE/java.lines" && echo "S1 RESULT: Swift == JCA on all $(wc -l < "$HERE/swift.lines") vectors"
echo "## S2: swift-certificates parse + chain signature (partial S-A10)"; (cd "$HERE/CertSpike" && swift run -q CertSpike ../certs 2>&1 | tail -8)
echo "## S3: XcodeGen project generation on Linux"; XG="$HERE/xcodegen-try/XcodeGen"; (cd "$XG" && USER=builder ./.build/release/xcodegen generate --spec "$HERE/projgen/project.yml" --project "$HERE/projgen" 2>&1 | tail -1)
swift --version 2>&1 | head -1
