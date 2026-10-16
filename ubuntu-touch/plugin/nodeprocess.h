#pragma once

#include <QByteArray>
#include <QObject>
#include <QProcess>
#include <QString>
#include <QStringList>

/*
 * The UI side of asom-ut-ctl/1 (ubuntu-touch.md 7.3): spawns the bundled JVM, exchanges one JSON object per line over its
 * stdin and stdout, and enforces the 1 MiB line cap in both directions. It parses nothing: frames are handed to QML as text.
 * It listens on nothing and dials nothing; the only process it starts is the bundled runtime after its hashes checked out.
 */
class NodeProcess : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY runningChanged)
    Q_PROPERTY(QString installDir READ installDir WRITE setInstallDir NOTIFY installDirChanged)
    /* Tests only: a complete argv that replaces the bundled runtime. Production QML never sets it. */
    Q_PROPERTY(QStringList testCommand READ testCommand WRITE setTestCommand NOTIFY testCommandChanged)
    Q_PROPERTY(QString lastDiag READ lastDiag NOTIFY lastDiagChanged)
    Q_PROPERTY(QString jarSha256 READ jarSha256 NOTIFY hashesChanged)
    Q_PROPERTY(QString runtimeManifestSha256 READ runtimeManifestSha256 NOTIFY hashesChanged)

public:
    static constexpr int kMaxLineBytes = 1 << 20;

    explicit NodeProcess(QObject *parent = nullptr);
    ~NodeProcess() override;

    bool running() const;
    QString installDir() const { return m_installDir; }
    void setInstallDir(const QString &dir);
    QStringList testCommand() const { return m_testCommand; }
    void setTestCommand(const QStringList &argv);
    QString lastDiag() const { return m_lastDiag; }
    QString jarSha256() const { return m_jarSha256; }
    QString runtimeManifestSha256() const { return m_rtManifestSha256; }

    /* Checks rt.sha256 and jar.sha256, then spawns the JVM. Returns false and emits failed(reason) with a fixed reason. */
    Q_INVOKABLE bool start();
    /* Writes the line and a newline. Refuses a line over the cap or holding a line break. */
    Q_INVOKABLE bool send(const QString &jsonLine);
    /* Sends {"t":"shutdown"}, waits up to one second, then kills the child. */
    Q_INVOKABLE void stop();

    /* Pure helpers, public so that the tests can drive them without a process. */
    static QStringList expandOptions(const QByteArray &optionsFile, const QHash<QString, QString> &env);
    static QString verifyManifest(const QString &baseDir, const QByteArray &manifest, int *fileCount);
    static bool splitLines(QByteArray *buffer, QList<QByteArray> *lines, int maxLine);

signals:
    void runningChanged();
    void installDirChanged();
    void testCommandChanged();
    void lastDiagChanged();
    void hashesChanged();
    void lineReceived(const QString &line);
    void started();
    void exited(int exitCode);
    /* reason is one of: runtime-missing, runtime-hash-mismatch, jar-missing, jar-hash-mismatch, options-missing, spawn-failed,
       line-too-long, bad-encoding. Never a path, never any content. */
    void failed(const QString &reason);

private:
    void onReadyRead();
    void onErrorOutput();
    void onFinished(int exitCode, QProcess::ExitStatus status);
    void fail(const QString &reason);

    QProcess *m_process = nullptr;
    QString m_installDir;
    QStringList m_testCommand;
    QString m_lastDiag;
    QString m_jarSha256;
    QString m_rtManifestSha256;
    QByteArray m_buffer;
};
