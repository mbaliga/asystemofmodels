#pragma once

#include <QObject>
#include <QString>
#include <QTimer>
#include <Qt>

/*
 * Forwards the Qt application state to the node as `active`, `inactive` or `suspending` (ubuntu-touch.md 3.3, 7.3), and beats
 * once a second while `active` so the node's "active within the last 10 s" rule (L-UT1) holds. Whether Qt reports the change
 * before the shell's SIGSTOP arrives is assumption UA03, NEEDS-DEVICE-VALIDATION (DV-UT04); the node's watchdog covers it.
 */
class LifecycleForwarder : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool heartbeatRunning READ heartbeatRunning NOTIFY stateChanged)

public:
    explicit LifecycleForwarder(QObject *parent = nullptr);

    QString state() const { return m_state; }
    bool heartbeatRunning() const { return m_timer.isActive(); }

    /* Pure mapping, public for the tests: Active -> active; Inactive -> inactive; Suspended and Hidden -> suspending. */
    static QString wireState(Qt::ApplicationState s);

    /* Test hook: behaves exactly as if Qt had reported the state. */
    Q_INVOKABLE void simulate(const QString &wireState);

signals:
    void stateChanged(const QString &state);
    /* Emitted on every change and once a second while the state is `active`. */
    void report(const QString &state);

private:
    void apply(const QString &wire);

    QString m_state;
    QTimer m_timer;
};
