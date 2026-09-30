#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QtTest>

#include "displaykeeper.h"
#include "lifecycle.h"
#include "nodeprocess.h"

static QByteArray sha256Hex(const QByteArray &data)
{
    return QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex();
}

static void writeFile(const QString &path, const QByteArray &data, QFileDevice::Permissions extra = {})
{
    QDir().mkpath(QFileInfo(path).absolutePath());
    QFile f(path);
    QVERIFY2(f.open(QIODevice::WriteOnly), qPrintable(path));
    f.write(data);
    f.close();
    if (extra)
        f.setPermissions(f.permissions() | extra);
}

class TstPlugin : public QObject
{
    Q_OBJECT

private slots:
    void expandOptions_data();
    void expandOptions();
    void splitLines();
    void verifyManifest();
    void sendRefusesBadLines();
    void readsFramesFromAChild();
    void aLineOverTheCapKillsTheChild();
    void aChildThatPrintsGarbageOnStderrIsNotEchoed();
    void bundledRuntimeIsHashCheckedBeforeItRuns();
    void displayKeeperWithoutAServiceFailsQuietly();
    void lifecycleMapping();
    void lifecycleHeartbeat();
};

void TstPlugin::expandOptions_data()
{
    QTest::addColumn<QByteArray>("file");
    QTest::addColumn<QStringList>("expected");
    QTest::newRow("comments and blanks") << QByteArray("# c\n\n-Xmx128m\n  # indented comment\n-XX:+UseSerialGC\n") << QStringList{"-Xmx128m", "-XX:+UseSerialGC"};
    QTest::newRow("variables") << QByteArray("-Djava.io.tmpdir=${TMPDIR}\n-XX:SharedArchiveFile=${XDG_CACHE_HOME}/x/cds.jsa\n")
                               << QStringList{"-Djava.io.tmpdir=/run/t", "-XX:SharedArchiveFile=/cache/x/cds.jsa"};
    QTest::newRow("unset variable is empty") << QByteArray("-Da=${NOPE}b\n") << QStringList{"-Da=b"};
    QTest::newRow("no shell interpretation") << QByteArray("-Da=$(touch /tmp/x);`id`\n") << QStringList{"-Da=$(touch /tmp/x);`id`"};
}

void TstPlugin::expandOptions()
{
    QFETCH(QByteArray, file);
    QFETCH(QStringList, expected);
    const QHash<QString, QString> env{{"TMPDIR", "/run/t"}, {"XDG_CACHE_HOME", "/cache"}, {"HOME", "/home/u"}};
    QCOMPARE(NodeProcess::expandOptions(file, env), expected);
}

void TstPlugin::splitLines()
{
    QByteArray buf("a\nbb\nccc");
    QList<QByteArray> lines;
    QVERIFY(NodeProcess::splitLines(&buf, &lines, 10));
    QCOMPARE(lines, (QList<QByteArray>{"a", "bb"}));
    QCOMPARE(buf, QByteArray("ccc"));
    lines.clear();
    buf = QByteArray(10, 'x') + "\n";
    QVERIFY2(NodeProcess::splitLines(&buf, &lines, 10), "a line of exactly the cap is allowed");
    buf = QByteArray(11, 'x') + "\n";
    QVERIFY2(!NodeProcess::splitLines(&buf, &lines, 10), "a complete line over the cap is refused");
    buf = QByteArray(11, 'x');
    QVERIFY2(!NodeProcess::splitLines(&buf, &lines, 10), "an unfinished line over the cap is refused before its newline arrives");
    buf.clear();
    QVERIFY(NodeProcess::splitLines(&buf, &lines, 10));
}

void TstPlugin::verifyManifest()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString base = dir.path();
    writeFile(base + "/bin/java", "java-bytes");
    writeFile(base + "/lib/libjvm.so", "jvm-bytes");
    const QByteArray good = sha256Hex("java-bytes") + "  ./bin/java\n" + sha256Hex("jvm-bytes") + "  ./lib/libjvm.so\n";
    int n = 0;
    QCOMPARE(NodeProcess::verifyManifest(base, good, &n), QString());
    QCOMPARE(n, 2);
    writeFile(base + "/lib/libjvm.so", "jvm-bytez");
    QCOMPARE(NodeProcess::verifyManifest(base, good, nullptr), QStringLiteral("mismatch"));
    writeFile(base + "/lib/libjvm.so", "jvm-bytes");
    QFile::remove(base + "/bin/java");
    QCOMPARE(NodeProcess::verifyManifest(base, good, nullptr), QStringLiteral("missing"));
    QCOMPARE(NodeProcess::verifyManifest(base, QByteArray(), nullptr), QStringLiteral("bad-manifest"));
    QCOMPARE(NodeProcess::verifyManifest(base, QByteArray("nothex  ./x\n"), nullptr), QStringLiteral("bad-manifest"));
    QCOMPARE(NodeProcess::verifyManifest(base, sha256Hex("x") + "  ./../etc/passwd\n", nullptr), QStringLiteral("bad-manifest"));
    QCOMPARE(NodeProcess::verifyManifest(base, sha256Hex("x") + "  /etc/passwd\n", nullptr), QStringLiteral("bad-manifest"));
    QTemporaryDir outside;
    writeFile(outside.path() + "/secret", "s");
    QVERIFY(QFile::link(outside.path() + "/secret", base + "/lib/escape"));
    QCOMPARE(NodeProcess::verifyManifest(base, sha256Hex("s") + "  ./lib/escape\n", nullptr), QStringLiteral("missing"));
}

void TstPlugin::sendRefusesBadLines()
{
    NodeProcess p;
    p.setTestCommand({"/bin/cat"});
    QVERIFY2(!p.send("{}"), "not running yet");
    QVERIFY(p.start());
    QVERIFY(p.send("{\"t\":\"hello\",\"v\":1}"));
    QVERIFY(!p.send("a\nb"));
    QVERIFY(!p.send("a\rb"));
    QVERIFY(!p.send(QString(NodeProcess::kMaxLineBytes + 1, 'a')));
    QVERIFY(p.send(QString(NodeProcess::kMaxLineBytes, 'a')));
    p.stop();
    QVERIFY(!p.running());
}

void TstPlugin::readsFramesFromAChild()
{
    NodeProcess p;
    p.setTestCommand({"/bin/sh", "-c", "printf '{\"t\":\"a\"}\\n{\"t\":\"b\"}\\n'; cat"});
    QSignalSpy lines(&p, &NodeProcess::lineReceived);
    QVERIFY(p.start());
    QTRY_COMPARE(lines.count(), 2);
    QCOMPARE(lines.at(0).at(0).toString(), QStringLiteral("{\"t\":\"a\"}"));
    QCOMPARE(lines.at(1).at(0).toString(), QStringLiteral("{\"t\":\"b\"}"));
    QVERIFY(p.send("{\"t\":\"c\"}"));
    QTRY_COMPARE(lines.count(), 3);
    QSignalSpy exited(&p, &NodeProcess::exited);
    p.stop();
    QTRY_VERIFY(exited.count() >= 1 || !p.running());
}

void TstPlugin::aLineOverTheCapKillsTheChild()
{
    NodeProcess p;
    p.setTestCommand({"/bin/sh", "-c", "head -c 2000000 /dev/zero | tr '\\0' a; sleep 30"});
    QSignalSpy failed(&p, &NodeProcess::failed);
    QSignalSpy exited(&p, &NodeProcess::exited);
    QVERIFY(p.start());
    QTRY_VERIFY_WITH_TIMEOUT(failed.count() == 1, 10000);
    QCOMPARE(failed.at(0).at(0).toString(), QStringLiteral("line-too-long"));
    QTRY_VERIFY_WITH_TIMEOUT(exited.count() == 1, 10000);
}

void TstPlugin::aChildThatPrintsGarbageOnStderrIsNotEchoed()
{
    NodeProcess p;
    p.setTestCommand({"/bin/sh", "-c", "echo 'SECRET-TRACE at com.example' >&2; echo 'asom-ut: internal error' >&2; sleep 1"});
    QSignalSpy diag(&p, &NodeProcess::lastDiagChanged);
    QVERIFY(p.start());
    QTRY_VERIFY(diag.count() >= 1);
    QCOMPARE(p.lastDiag(), QStringLiteral("asom-ut: internal error"));
    p.stop();
}

void TstPlugin::bundledRuntimeIsHashCheckedBeforeItRuns()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString base = dir.path();
    const QString rt = base + "/lib/asom/rt";
    const QByteArray fakeJava = "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"$a\"; done\n";
    writeFile(rt + "/bin/java", fakeJava, QFileDevice::ExeOwner);
    writeFile(base + "/lib/asom/asom-ut-node.jar", "jar-bytes");
    writeFile(base + "/lib/asom/jvm.options", "-Xmx128m\n-Djava.io.tmpdir=${TMPDIR}\n");
    writeFile(base + "/lib/asom/rt.sha256", sha256Hex(fakeJava) + "  ./bin/java\n");
    writeFile(base + "/lib/asom/jar.sha256", sha256Hex("jar-bytes") + "  asom-ut-node.jar\n");

    {
        NodeProcess p;
        p.setInstallDir(base);
        QSignalSpy lines(&p, &NodeProcess::lineReceived);
        QSignalSpy failed(&p, &NodeProcess::failed);
        QVERIFY2(p.start(), "an intact install must start");
        QTRY_VERIFY(lines.count() >= 5);
        QStringList args;
        for (const QList<QVariant> &l : lines)
            args << l.at(0).toString();
        QCOMPARE(args.mid(0, 1), QStringList{"-Xmx128m"});
        QVERIFY(args.contains("-jar"));
        QVERIFY(args.contains(base + "/lib/asom/asom-ut-node.jar"));
        QCOMPARE(args.last(), QStringLiteral("--profile=ut"));
        QCOMPARE(p.jarSha256(), QString::fromLatin1(sha256Hex("jar-bytes")));
        QCOMPARE(failed.count(), 0);
    }
    {
        writeFile(rt + "/bin/java", fakeJava + "# tampered\n", QFileDevice::ExeOwner);
        NodeProcess p;
        p.setInstallDir(base);
        QSignalSpy failed(&p, &NodeProcess::failed);
        QVERIFY2(!p.start(), "a changed runtime file must not run");
        QCOMPARE(failed.at(0).at(0).toString(), QStringLiteral("runtime-hash-mismatch"));
        writeFile(rt + "/bin/java", fakeJava, QFileDevice::ExeOwner);
    }
    {
        writeFile(base + "/lib/asom/asom-ut-node.jar", "jar-bytez");
        NodeProcess p;
        p.setInstallDir(base);
        QSignalSpy failed(&p, &NodeProcess::failed);
        QVERIFY2(!p.start(), "a changed jar must not run");
        QCOMPARE(failed.at(0).at(0).toString(), QStringLiteral("jar-hash-mismatch"));
    }
    {
        QFile::remove(rt + "/bin/java");
        NodeProcess p;
        p.setInstallDir(base);
        QSignalSpy failed(&p, &NodeProcess::failed);
        QVERIFY(!p.start());
        QCOMPARE(failed.at(0).at(0).toString(), QStringLiteral("runtime-missing"));
    }
}

void TstPlugin::displayKeeperWithoutAServiceFailsQuietly()
{
    DisplayKeeper k;
    k.setService("org.invalid.NoSuchScreenService");
    QVERIFY(!k.hold());
    QVERIFY(!k.held());
    k.release();
    QVERIFY(!k.held());
}

void TstPlugin::lifecycleMapping()
{
    QCOMPARE(LifecycleForwarder::wireState(Qt::ApplicationActive), QStringLiteral("active"));
    QCOMPARE(LifecycleForwarder::wireState(Qt::ApplicationInactive), QStringLiteral("inactive"));
    QCOMPARE(LifecycleForwarder::wireState(Qt::ApplicationSuspended), QStringLiteral("suspending"));
    QCOMPARE(LifecycleForwarder::wireState(Qt::ApplicationHidden), QStringLiteral("suspending"));
    LifecycleForwarder f;
    QSignalSpy report(&f, &LifecycleForwarder::report);
    f.simulate("bogus");
    QCOMPARE(report.count(), 0);
    f.simulate("suspending");
    QCOMPARE(f.state(), QStringLiteral("suspending"));
    QCOMPARE(report.count(), 1);
    f.simulate("suspending");
    QCOMPARE(report.count(), 1);
}

void TstPlugin::lifecycleHeartbeat()
{
    LifecycleForwarder f;
    f.simulate("inactive");
    QVERIFY(!f.heartbeatRunning());
    QSignalSpy report(&f, &LifecycleForwarder::report);
    f.simulate("active");
    QVERIFY(f.heartbeatRunning());
    QTRY_VERIFY_WITH_TIMEOUT(report.count() >= 3, 4000);
    for (const QList<QVariant> &l : report)
        QCOMPARE(l.at(0).toString(), QStringLiteral("active"));
    f.simulate("inactive");
    QVERIFY2(!f.heartbeatRunning(), "no heartbeat while inactive: the node must see silence");
    const int n = report.count();
    QTest::qWait(1300);
    QCOMPARE(report.count(), n);
}

QTEST_GUILESS_MAIN(TstPlugin)
#include "tst_plugin.moc"
