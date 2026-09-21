import SwiftUI

private let rutuGold = Color(red: 0.90, green: 0.72, blue: 0.36)
private let rutuGoldLight = Color(red: 0.96, green: 0.82, blue: 0.48)
private let rutuPanel = Color(red: 0.09, green: 0.08, blue: 0.06)

struct ContentView: View {
    @EnvironmentObject var store: RutuStore
    @State private var role: Role?

    enum Role { case customer, business }

    var body: some View {
        ZStack {
            LinearGradient(colors: [.black, Color(red: 0.12, green: 0.07, blue: 0.03), .black], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            if let role {
                switch role {
                case .customer: CustomerView(onBack: { self.role = nil })
                case .business: BusinessView(onBack: { self.role = nil })
                }
            } else {
                LandingView(customer: { role = .customer }, business: { role = .business })
            }
        }
        .tint(rutuGold)
    }
}

struct LandingView: View {
    let customer: () -> Void
    let business: () -> Void

    var body: some View {
        ScrollView { VStack(spacing: 20) {
            Spacer()
            Image("RutuLogo")
                .resizable().scaledToFit()
                .frame(maxWidth: 300, maxHeight: 280)
                .accessibilityLabel("Rutu BBQ — gouden levensboom met vlammen")
            VStack(spacing: 7) {
                Text("Made in fire. Unique taste.").font(.system(size: 30, weight: .bold, design: .serif)).foregroundStyle(rutuGoldLight)
                Text("More than food. It’s an experience.").foregroundStyle(.secondary).italic()
            }
            VStack(spacing: 12) {
                RutuButton(title: "Bestellen", icon: "bag.fill", action: customer)
                RutuButton(title: "Bedrijfsmodus", icon: "storefront.fill", action: business, secondary: true)
            }
            .padding(.horizontal, 28)
            Spacer()
            Text("Rutu BBQ • iOS testomgeving").font(.footnote).foregroundStyle(.secondary)
        }
        .padding(.vertical, 24).padding(.horizontal, 16) }
    }
}

struct CustomerView: View {
    @EnvironmentObject var store: RutuStore
    let onBack: () -> Void
    @State private var showCart = false
    @State private var showOrders = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HeroCard(title: "Fire. Roots. Flavour.", subtitle: "Kies je favorieten en bestel bij Rutu BBQ.")
                    Text("Ons menu").font(.system(.title2, design: .serif, weight: .bold)).foregroundStyle(rutuGoldLight)
                    ForEach(store.products) { product in ProductCard(product: product) }
                    Color.clear.frame(height: 80)
                }.padding()
            }
            .background(Color.black.opacity(0.25))
            .navigationTitle("Rutu BBQ")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Terug", action: onBack) }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { showOrders = true } label: { Image(systemName: "clock.arrow.circlepath") }
                    Button { showCart = true } label: { Label("\(store.cartCount)", systemImage: "cart.fill") }
                }
            }
            .sheet(isPresented: $showCart) { CartView() }
            .sheet(isPresented: $showOrders) { OrdersView() }
        }
    }
}

struct ProductCard: View {
    @EnvironmentObject var store: RutuStore
    let product: Product

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 16).fill(LinearGradient(colors: [Color.orange.opacity(0.35), rutuGold.opacity(0.12)], startPoint: .top, endPoint: .bottom))
                Image(systemName: product.category == "Dessert" ? "birthday.cake.fill" : product.category == "Drinks" ? "cup.and.saucer.fill" : "flame.fill")
                    .font(.title).foregroundStyle(product.category == "Dessert" ? rutuGoldLight : .orange)
            }.frame(width: 72, height: 72)
            VStack(alignment: .leading, spacing: 5) {
                Text(product.name).font(.headline).fixedSize(horizontal: false, vertical: true)
                Text(product.category).font(.caption).foregroundStyle(.secondary)
                Text(product.price, format: .currency(code: "EUR")).font(.subheadline.bold()).foregroundStyle(rutuGoldLight)
            }
            Spacer()
            VStack(spacing: 8) {
                Button { store.add(product) } label: { Image(systemName: "plus").frame(width: 44, height: 44) }.buttonStyle(.borderedProminent)
                if store.cart[product.name, default: 0] > 0 {
                    Text("\(store.cart[product.name, default: 0])×").font(.caption.bold())
                }
            }
        }
        .padding(14).background(rutuPanel).clipShape(RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.07)))
    }
}

struct CartView: View {
    @EnvironmentObject var store: RutuStore
    @Environment(\.dismiss) var dismiss
    @State private var confirmation: RutuOrder?

    var body: some View {
        NavigationStack {
            VStack {
                if store.cart.isEmpty {
                    ContentUnavailableView("Je winkelmand is leeg", systemImage: "cart")
                } else {
                    List {
                        ForEach(store.products.filter { store.cart[$0.name] != nil }) { product in
                            HStack {
                                VStack(alignment: .leading) { Text(product.name).bold(); Text(product.price, format: .currency(code: "EUR")).foregroundStyle(.secondary) }
                                Spacer()
                                Button { store.subtract(product) } label: { Image(systemName: "minus.circle") }
                                Text("\(store.cart[product.name, default: 0])")
                                Button { store.add(product) } label: { Image(systemName: "plus.circle") }
                            }
                        }
                    }
                    VStack(spacing: 12) {
                        HStack { Text("Totaal").font(.headline); Spacer(); Text(store.cartTotal, format: .currency(code: "EUR")).font(.title3.bold()).foregroundStyle(rutuGoldLight) }
                        RutuButton(title: "Bestelling plaatsen", icon: "flame.fill") {
                            confirmation = store.placeOrder()
                        }
                    }.padding()
                }
            }
            .navigationTitle("Winkelmand")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Sluiten") { dismiss() } } }
            .alert("Bestelling geplaatst", isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } })) {
                Button("OK") { confirmation = nil; dismiss() }
            } message: { Text("Bestelling #\(confirmation?.id ?? 0) is ontvangen door Rutu BBQ.") }
        }
    }
}

struct OrdersView: View {
    @EnvironmentObject var store: RutuStore
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            List(store.orders) { order in OrderSummary(order: order) }
                .overlay { if store.orders.isEmpty { ContentUnavailableView("Nog geen bestellingen", systemImage: "takeoutbag.and.cup.and.straw") } }
                .navigationTitle("Mijn bestellingen")
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Sluiten") { dismiss() } } }
        }
    }
}

struct BusinessView: View {
    @EnvironmentObject var store: RutuStore
    let onBack: () -> Void

    var body: some View {
        NavigationStack {
            List {
                HeroCard(title: "Jouw keuken", subtitle: "Alles in beeld. Met aandacht bereid.").listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                Section("Overzicht") {
                    HStack { Label("Nieuw", systemImage: "bell.badge.fill"); Spacer(); Text("\(store.orders.filter { $0.status == "Nieuw" }.count)").bold().foregroundStyle(.orange) }
                    HStack { Label("In bereiding", systemImage: "flame.fill"); Spacer(); Text("\(store.orders.filter { $0.status == "In bereiding" }.count)").bold() }
                    HStack { Label("Klaar", systemImage: "checkmark.circle.fill"); Spacer(); Text("\(store.orders.filter { $0.status == "Klaar" }.count)").bold().foregroundStyle(.green) }
                }
                Section("Bestellingen") {
                    ForEach(store.orders) { order in
                        VStack(alignment: .leading, spacing: 10) {
                            OrderSummary(order: order)
                            HStack {
                                switch order.status {
                                case "Nieuw":
                                    Button("Accepteren") { store.setStatus(order.id, "In bereiding") }.buttonStyle(.borderedProminent)
                                    Button("Weigeren", role: .destructive) { store.setStatus(order.id, "Geweigerd") }.buttonStyle(.bordered)
                                case "In bereiding": Button("Klaar") { store.setStatus(order.id, "Klaar") }.buttonStyle(.borderedProminent)
                                case "Klaar": Button("Afronden") { store.setStatus(order.id, "Afgerond") }.buttonStyle(.borderedProminent)
                                default: EmptyView()
                                }
                            }
                        }.padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("Rutu BBQ • Bedrijf")
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Terug", action: onBack) } }
            .overlay { if store.orders.isEmpty { ContentUnavailableView("Wachten op bestellingen", systemImage: "storefront") } }
        }
    }
}

struct OrderSummary: View {
    let order: RutuOrder
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack { Text("#\(order.id)").font(.headline); Spacer(); Text(order.total, format: .currency(code: "EUR")).bold().foregroundStyle(rutuGoldLight) }
            Text(order.items.sorted(by: { $0.key < $1.key }).map { "\($0.value)× \($0.key)" }.joined(separator: " • ")).font(.subheadline).foregroundStyle(.secondary)
            Text(order.status).font(.caption.bold()).padding(.horizontal, 9).padding(.vertical, 5).background(rutuGold.opacity(0.17)).clipShape(Capsule())
        }
    }
}

struct HeroCard: View {
    let title: String
    let subtitle: String
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 7) { Text(title).font(.title2.bold()).foregroundStyle(rutuGoldLight); Text(subtitle).foregroundStyle(.secondary) }
            Spacer(); Image("RutuLogo").resizable().scaledToFit().frame(width: 88, height: 88).accessibilityHidden(true)
        }
        .padding(20).background(LinearGradient(colors: [Color.orange.opacity(0.16), rutuGold.opacity(0.08), rutuPanel], startPoint: .topLeading, endPoint: .bottomTrailing)).clipShape(RoundedRectangle(cornerRadius: 22)).overlay(RoundedRectangle(cornerRadius: 22).stroke(rutuGold.opacity(0.25)))
    }
}

struct RutuButton: View {
    let title: String
    let icon: String
    var secondary = false
    let action: () -> Void
    var body: some View {
        Button(action: action) { Label(title, systemImage: icon).font(.headline).frame(maxWidth: .infinity, minHeight: 44).padding(.vertical, 7) }
            .buttonStyle(.borderedProminent).tint(secondary ? rutuPanel : rutuGold)
            .foregroundStyle(secondary ? Color.white : Color.black)
    }
}
