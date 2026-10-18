#include "lifecycle.h"

#include <QCoreApplication>
#include <QGuiApplication>

LifecycleForwarder::LifecycleForwarder(QObject *parent)
    : QObject(parent)
{
    m_timer.setInterval(1000);
    m_timer.setTimerType(Qt::CoarseTimer);
    connect(&m_timer, &QTimer::timeout, this, [this]() { emit report(m_state); });
    if (auto *app = qobject_cast<QGuiApplication *>(QCoreApplication::instance())) {
        connect(app, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState s) { apply(wireState(s)); });
        m_state = wireState(app->applicationState());
        if (m_state == QLatin1String("active"))
            m_timer.start();
    } else {
        m_state = QStringLiteral("inactive");
    }
}

QString LifecycleForwarder::wireState(Qt::ApplicationState s)
{
    switch (s) {
    case Qt::ApplicationActive:
        return QStringLiteral("active");
    case Qt::ApplicationInactive:
        return QStringLiteral("inactive");
    case Qt::ApplicationSuspended:
    case Qt::ApplicationHidden:
        return QStringLiteral("suspending");
    }
    return QStringLiteral("inactive");
}

void LifecycleForwarder::simulate(const QString &wire)
{
    if (wire == QLatin1String("active") || wire == QLatin1String("inactive") || wire == QLatin1String("suspending"))
        apply(wire);
}

void LifecycleForwarder::apply(const QString &wire)
{
    if (wire == m_state)
        return;
    m_state = wire;
    if (wire == QLatin1String("active"))
        m_timer.start();
    else
        m_timer.stop();
    emit stateChanged(m_state);
    emit report(m_state);
}
