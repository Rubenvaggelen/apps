import SwiftUI

@main
struct RutuBBQApp: App {
    @StateObject private var store = RutuStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}
