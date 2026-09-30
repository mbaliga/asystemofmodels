#include "displaykeeper.h"

#include <QDBusConnection>
#include <QDBusInterface>
#include <QDBusReply>

namespace {
const char kService[] = "com.canonical.Unity.Screen";
const char kPath[] = "/com/canonical/Unity/Screen";
const char kInterface[] = "com.canonical.Unity.Screen";
}

DisplayKeeper::DisplayKeeper(QObject *parent)
    : QObject(parent)
    , m_service(QString::fromLatin1(kService))
{
}

DisplayKeeper::~DisplayKeeper()
{
    release();
}

void DisplayKeeper::setService(const QString &service)
{
    if (service == m_service)
        return;
    release();
    m_service = service;
    emit serviceChanged();
}

bool DisplayKeeper::hold()
{
    if (held())
        return true;
    QDBusConnection bus = QDBusConnection::systemBus();
    if (!bus.isConnected())
        return false;
    QDBusInterface screen(m_service, QString::fromLatin1(kPath), QString::fromLatin1(kInterface), bus);
    if (!screen.isValid())
        return false;
    const QDBusReply<int> reply = screen.call(QStringLiteral("keepDisplayOn"));
    if (!reply.isValid())
        return false;
    m_cookie = reply.value();
    emit heldChanged();
    return true;
}

void DisplayKeeper::release()
{
    if (!held())
        return;
    const int cookie = m_cookie;
    m_cookie = -1;
    emit heldChanged();
    QDBusConnection bus = QDBusConnection::systemBus();
    if (!bus.isConnected())
        return;
    QDBusInterface screen(m_service, QString::fromLatin1(kPath), QString::fromLatin1(kInterface), bus);
    if (screen.isValid())
        screen.call(QStringLiteral("removeDisplayOnRequest"), cookie);
}
