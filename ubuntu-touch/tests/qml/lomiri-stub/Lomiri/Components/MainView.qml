import QtQuick 2.12

// LOCAL STAND-IN for Lomiri.Components (not installable outside a UBports image). Used only by tools/run_qml_tests_local.sh so the
// page logic can be exercised without the toolkit; layout and styling are NOT verified by it. CI runs the real module.
Item {
    property string applicationName
    property color backgroundColor: "black"
}
