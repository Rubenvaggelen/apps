import Foundation

struct Product: Identifiable, Hashable {
    let id = UUID()
    let name: String
    let price: Double
    let category: String
}

struct RutuOrder: Identifiable, Codable, Hashable {
    let id: Int
    var items: [String: Int]
    var total: Double
    var status: String
    var createdAt: Date
}

@MainActor
final class RutuStore: ObservableObject {
    @Published var cart: [String: Int] = [:]
    @Published var orders: [RutuOrder] = []

    let products: [Product] = [
        .init(name: "Teriyaki Chicken", price: 12.50, category: "BBQ"),
        .init(name: "The Emperor Burger", price: 14.95, category: "BBQ"),
        .init(name: "Nasi Special", price: 11.50, category: "Meals"),
        .init(name: "Roti Kip", price: 13.50, category: "Meals"),
        .init(name: "Friet groot", price: 4.25, category: "Sides"),
        .init(name: "Ube Cheesecake", price: 6.95, category: "Dessert"),
        .init(name: "Cola", price: 2.75, category: "Drinks"),
        .init(name: "Iced Tea", price: 2.75, category: "Drinks")
    ]

    init() { load() }

    func add(_ product: Product) { cart[product.name, default: 0] += 1 }
    func subtract(_ product: Product) {
        guard let quantity = cart[product.name] else { return }
        if quantity <= 1 { cart.removeValue(forKey: product.name) }
        else { cart[product.name] = quantity - 1 }
    }

    var cartCount: Int { cart.values.reduce(0, +) }
    var cartTotal: Double {
        cart.reduce(0) { result, entry in
            result + (products.first(where: { $0.name == entry.key })?.price ?? 0) * Double(entry.value)
        }
    }

    @discardableResult
    func placeOrder() -> RutuOrder? {
        guard !cart.isEmpty else { return nil }
        let next = max(1045, orders.map(\.id).max() ?? 1045) + 1
        let order = RutuOrder(id: next, items: cart, total: cartTotal, status: "Nieuw", createdAt: Date())
        orders.insert(order, at: 0)
        cart.removeAll()
        save()
        return order
    }

    func setStatus(_ id: Int, _ status: String) {
        guard let index = orders.firstIndex(where: { $0.id == id }) else { return }
        orders[index].status = status
        save()
    }

    private var storageURL: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.appendingPathComponent("rutu-orders.json")
    }

    private func load() {
        guard let url = storageURL, let data = try? Data(contentsOf: url), let saved = try? JSONDecoder().decode([RutuOrder].self, from: data) else { return }
        orders = saved
    }

    private func save() {
        guard let url = storageURL, let data = try? JSONEncoder().encode(orders) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
