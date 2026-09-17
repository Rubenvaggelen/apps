import SwiftUI

extension RutuButton {
    init(title: String, icon: String, action: @escaping () -> Void, secondary: Bool) {
        self.title = title
        self.icon = icon
        self.secondary = secondary
        self.action = action
    }
}
