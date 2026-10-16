#include <QQmlEngine>
#include <QQmlExtensionPlugin>

#include "displaykeeper.h"
#include "lifecycle.h"
#include "nodeprocess.h"

class AsomBridgePlugin : public QQmlExtensionPlugin
{
    Q_OBJECT
    Q_PLUGIN_METADATA(IID QQmlExtensionInterface_iid)

public:
    void registerTypes(const char *uri) override
    {
        Q_ASSERT(QLatin1String(uri) == QLatin1String("Asom.Bridge"));
        qmlRegisterType<NodeProcess>(uri, 1, 0, "NodeProcess");
        qmlRegisterType<DisplayKeeper>(uri, 1, 0, "DisplayKeeper");
        qmlRegisterType<LifecycleForwarder>(uri, 1, 0, "LifecycleForwarder");
    }
};

#include "plugin.moc"
