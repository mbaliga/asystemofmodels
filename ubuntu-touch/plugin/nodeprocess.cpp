#include "nodeprocess.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QProcessEnvironment>
#include <QRegularExpression>
#include <QSet>
#include <QTimer>

#include <cstring>

namespace {

const char kDiagPrefix[] = "asom-ut: ";

QByteArray fileSha256(const QString &path, bool *ok)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        *ok = false;
        return QByteArray();
    }
    QCryptographicHash h(QCryptographicHash::Sha256);
    if (!h.addData(&f)) {
        *ok = false;
        return QByteArray();
    }
    *ok = true;
    return h.result().toHex();
}

}

NodeProcess::NodeProcess(QObject *parent)
    : QObject(parent)
{
}

NodeProcess::~NodeProcess()
{
    if (m_process && m_process->state() != QProcess::NotRunning) {
        m_process->disconnect(this);
        m_process->kill();
        m_process->waitForFinished(1000);
    }
}

bool NodeProcess::running() const
{
    return m_process && m_process->state() == QProcess::Running;
}

void NodeProcess::setInstallDir(const QString &dir)
{
    if (dir == m_installDir)
        return;
    m_installDir = dir;
    emit installDirChanged();
}

void NodeProcess::setTestCommand(const QStringList &argv)
{
    if (argv == m_testCommand)
        return;
    m_testCommand = argv;
    emit testCommandChanged();
}

/* Lines of jvm.options: '#' starts a comment; ${NAME} comes from env; an unset XDG_CACHE_HOME falls back to $HOME/.cache. */
QStringList NodeProcess::expandOptions(const QByteArray &optionsFile, const QHash<QString, QString> &env)
{
    QStringList out;
    static const QRegularExpression var(QStringLiteral("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}"));
    const QList<QByteArray> lines = optionsFile.split('\n');
    for (const QByteArray &raw : lines) {
        QString line = QString::fromUtf8(raw).trimmed();
        if (line.isEmpty() || line.startsWith(QLatin1Char('#')))
            continue;
        QString expanded;
        int last = 0;
        QRegularExpressionMatchIterator it = var.globalMatch(line);
        while (it.hasNext()) {
            const QRegularExpressionMatch m = it.next();
            expanded += line.mid(last, m.capturedStart() - last);
            const QString name = m.captured(1);
            QString value = env.value(name);
            if (value.isEmpty() && name == QLatin1String("XDG_CACHE_HOME") && !env.value(QStringLiteral("HOME")).isEmpty())
                value = env.value(QStringLiteral("HOME")) + QStringLiteral("/.cache");
            expanded += value;
            last = m.capturedEnd();
        }
        expanded += line.mid(last);
        out << expanded;
    }
    return out;
}

/* Checks a sha256sum-format manifest ("<hex>  ./relative/path") against the files under baseDir. Returns an empty string
   when every listed file exists and matches, else a fixed reason. Paths that leave baseDir are refused. */
QString NodeProcess::verifyManifest(const QString &baseDir, const QByteArray &manifest, int *fileCount)
{
    static const QRegularExpression lineRe(QStringLiteral("^([0-9a-f]{64})  (\\./.+)$"));
    const QDir base(baseDir);
    const QString canonicalBase = QFileInfo(baseDir).canonicalFilePath();
    int n = 0;
    for (const QByteArray &raw : manifest.split('\n')) {
        const QString line = QString::fromUtf8(raw);
        if (line.isEmpty())
            continue;
        const QRegularExpressionMatch m = lineRe.match(line);
        if (!m.hasMatch())
            return QStringLiteral("bad-manifest");
        const QString rel = m.captured(2);
        if (rel.contains(QStringLiteral("/../")) || rel.endsWith(QStringLiteral("/..")))
            return QStringLiteral("bad-manifest");
        const QString full = base.filePath(rel);
        const QString canonical = QFileInfo(full).canonicalFilePath();
        if (canonical.isEmpty() || canonicalBase.isEmpty() || !canonical.startsWith(canonicalBase + QLatin1Char('/')))
            return QStringLiteral("missing");
        bool ok = false;
        if (QString::fromLatin1(fileSha256(canonical, &ok)) != m.captured(1) || !ok)
            return QStringLiteral("mismatch");
        ++n;
    }
    if (n == 0)
        return QStringLiteral("bad-manifest");
    if (fileCount)
        *fileCount = n;
    return QString();
}

/* Moves every complete line out of *buffer. Returns false, and leaves *buffer unusable, when a line (complete or not yet
   complete) is longer than maxLine: the caller must then kill the child. */
bool NodeProcess::splitLines(QByteArray *buffer, QList<QByteArray> *lines, int maxLine)
{
    int start = 0;
    for (;;) {
        const int nl = buffer->indexOf('\n', start);
        if (nl < 0)
            break;
        if (nl - start > maxLine)
            return false;
        lines->append(buffer->mid(start, nl - start));
        start = nl + 1;
    }
    buffer->remove(0, start);
    return buffer->size() <= maxLine;
}

void NodeProcess::fail(const QString &reason)
{
    emit failed(reason);
}

bool NodeProcess::start()
{
    if (running())
        return true;
    QString program;
    QStringList args;
    if (!m_testCommand.isEmpty()) {
        program = m_testCommand.first();
        args = m_testCommand.mid(1);
    } else {
        const QDir base(m_installDir);
        const QString rt = base.filePath(QStringLiteral("lib/asom/rt"));
        const QString libAsom = base.filePath(QStringLiteral("lib/asom"));
        const QString jar = base.filePath(QStringLiteral("lib/asom/asom-ut-node.jar"));
        if (!QFileInfo(rt + QStringLiteral("/bin/java")).isExecutable() || !QFileInfo::exists(libAsom + QStringLiteral("/rt.sha256"))) {
            fail(QStringLiteral("runtime-missing"));
            return false;
        }
        if (!QFileInfo::exists(jar) || !QFileInfo::exists(libAsom + QStringLiteral("/jar.sha256"))) {
            fail(QStringLiteral("jar-missing"));
            return false;
        }
        QFile rtManifest(libAsom + QStringLiteral("/rt.sha256"));
        if (!rtManifest.open(QIODevice::ReadOnly)) {
            fail(QStringLiteral("runtime-missing"));
            return false;
        }
        const QByteArray rtBytes = rtManifest.readAll();
        if (!verifyManifest(rt, rtBytes, nullptr).isEmpty()) {
            fail(QStringLiteral("runtime-hash-mismatch"));
            return false;
        }
        QFile jarManifest(libAsom + QStringLiteral("/jar.sha256"));
        if (!jarManifest.open(QIODevice::ReadOnly)) {
            fail(QStringLiteral("jar-missing"));
            return false;
        }
        bool ok = false;
        const QString jarHash = QString::fromLatin1(fileSha256(jar, &ok));
        const QString want = QString::fromLatin1(jarManifest.readAll()).split(QLatin1Char(' ')).value(0).trimmed();
        if (!ok || jarHash != want) {
            fail(QStringLiteral("jar-hash-mismatch"));
            return false;
        }
        m_jarSha256 = jarHash;
        m_rtManifestSha256 = QString::fromLatin1(QCryptographicHash::hash(rtBytes, QCryptographicHash::Sha256).toHex());
        emit hashesChanged();
        QFile options(libAsom + QStringLiteral("/jvm.options"));
        if (!options.open(QIODevice::ReadOnly)) {
            fail(QStringLiteral("options-missing"));
            return false;
        }
        QHash<QString, QString> env;
        const QProcessEnvironment pe = QProcessEnvironment::systemEnvironment();
        for (const QString &k : pe.keys())
            env.insert(k, pe.value(k));
        const QStringList jvmArgs = expandOptions(options.readAll(), env);
        program = rt + QStringLiteral("/bin/java");
        args = jvmArgs;
        args << QStringLiteral("-jar") << jar << QStringLiteral("--profile=ut");
    }
    m_buffer.clear();
    m_process = new QProcess(this);
    QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
    env.remove(QStringLiteral("JAVA_TOOL_OPTIONS"));
    env.remove(QStringLiteral("JDK_JAVA_OPTIONS"));
    env.remove(QStringLiteral("_JAVA_OPTIONS"));
    m_process->setProcessEnvironment(env);
    m_process->setProgram(program);
    m_process->setArguments(args);
    connect(m_process, &QProcess::readyReadStandardOutput, this, &NodeProcess::onReadyRead);
    connect(m_process, &QProcess::readyReadStandardError, this, &NodeProcess::onErrorOutput);
    connect(m_process, QOverload<int, QProcess::ExitStatus>::of(&QProcess::finished), this, &NodeProcess::onFinished);
    m_process->start();
    if (!m_process->waitForStarted(5000)) {
        m_process->deleteLater();
        m_process = nullptr;
        fail(QStringLiteral("spawn-failed"));
        return false;
    }
    emit runningChanged();
    emit started();
    return true;
}

bool NodeProcess::send(const QString &jsonLine)
{
    if (!running() || jsonLine.contains(QLatin1Char('\n')) || jsonLine.contains(QLatin1Char('\r')))
        return false;
    const QByteArray bytes = jsonLine.toUtf8();
    if (bytes.size() > kMaxLineBytes)
        return false;
    m_process->write(bytes);
    m_process->write("\n", 1);
    return true;
}

void NodeProcess::stop()
{
    if (!m_process || m_process->state() == QProcess::NotRunning)
        return;
    send(QStringLiteral("{\"t\":\"shutdown\"}"));
    m_process->closeWriteChannel();
    if (!m_process->waitForFinished(1000))
        m_process->kill();
}

void NodeProcess::onReadyRead()
{
    m_buffer.append(m_process->readAllStandardOutput());
    QList<QByteArray> lines;
    const bool ok = splitLines(&m_buffer, &lines, kMaxLineBytes);
    for (const QByteArray &l : lines) {
        QString text = QString::fromUtf8(l);
        emit lineReceived(text);
    }
    if (!ok) {
        m_buffer.clear();
        m_process->kill();
        fail(QStringLiteral("line-too-long"));
    }
}

/* stderr carries only fixed diagnostics ("asom-ut: ..."). Anything else is dropped unread. */
void NodeProcess::onErrorOutput()
{
    const QByteArray all = m_process->readAllStandardError();
    for (const QByteArray &l : all.split('\n')) {
        if (l.startsWith(kDiagPrefix) && l.size() < 128) {
            m_lastDiag = QString::fromLatin1(l);
            emit lastDiagChanged();
        }
    }
}

void NodeProcess::onFinished(int exitCode, QProcess::ExitStatus)
{
    if (m_process) {
        m_process->deleteLater();
        m_process = nullptr;
    }
    emit runningChanged();
    emit exited(exitCode);
}
