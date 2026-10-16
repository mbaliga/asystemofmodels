#pragma once

#include <QObject>
#include <QString>

/*
 * Holds the display on while an answer is streaming, and only then (law L-UT2, ubuntu-touch.md 3.4): the UI takes a request
 * with hold() when a borrow is sent and gives it back with release() on the terminal frame, on cancel, and whenever the app
 * leaves the foreground. The only D-Bus calls are com.canonical.Unity.Screen.keepDisplayOn and removeDisplayOnRequest on the
 * system bus, which is exactly what the `keep-display-on` policy group allows (UF13). Whether holding the display keeps the
 * system out of suspend is assumption UA04, NEEDS-DEVICE-VALIDATION (DV-UT05).
 */
class DisplayKeeper : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool held READ held NOTIFY heldChanged)
    /* Tests only: the D-Bus service name, so a missing service can be simulated. Production leaves the default. */
    Q_PROPERTY(QString service READ service WRITE setService NOTIFY serviceChanged)

public:
    explicit DisplayKeeper(QObject *parent = nullptr);
    ~DisplayKeeper() override;

    bool held() const { return m_cookie >= 0; }
    QString service() const { return m_service; }
    void setService(const QString &service);

    /* Idempotent. Returns false when the service is absent or refuses; the display then simply is not held. */
    Q_INVOKABLE bool hold();
    Q_INVOKABLE void release();

signals:
    void heldChanged();
    void serviceChanged();

private:
    QString m_service;
    int m_cookie = -1;
};
