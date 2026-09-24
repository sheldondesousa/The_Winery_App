package com.sheldondesousa.uncork.ui.guided

/**
 * Curated country/region catalog for the Find location picker.
 * Source: Wikipedia — List of wine-producing regions (see Docs/wine_regions_table.md).
 */
object WineRegions {
    val catalog: Map<String, List<String>> = linkedMapOf(
        "Argentina" to listOf("Buenos Aires Province", "Catamarca Province", "La Rioja Province", "Mendoza Province", "Neuquén Province", "Río Negro Province", "Salta Province", "San Juan Province"),
        "Armenia" to listOf("Ararat Valley", "Areni", "Armavir", "Ijevan", "Voskevaz", "Yerevan"),
        "Australia" to listOf("New South Wales", "South Australia", "Tasmania", "Victoria"),
        "Austria" to listOf("Burgenland", "Lower Austria", "Styria", "Vienna"),
        "Azerbaijan" to listOf("Aghdam", "Baku", "Ganja", "Madrasa", "Tovuz & Shamkir"),
        "Belgium" to listOf("Côtes de Sambre et Meuse", "Hagelandse wijn", "Hainaut", "Haspengouw", "Heuvelland", "Liège", "Namur", "Province of Brabant"),
        "Brazil" to listOf("Bahia", "Mato Grosso", "Minas Gerais", "Paraná", "Pernambuco", "Rio Grande do Sul", "Santa Catarina", "São Paulo"),
        "Bulgaria" to listOf("Black Sea region", "Danubian Plain", "Rose Valley", "Struma River Valley", "Thrace"),
        "Canada" to listOf("British Columbia", "Nova Scotia", "Ontario", "Quebec"),
        "Chile" to listOf("Aconcagua", "Atacama", "Central Valley", "Coquimbo", "Southern Chile"),
        "China" to listOf("Chang'an", "Dalian", "Gaochang", "Luoyang", "Qiuci", "Tonghua", "Yantai", "Yantai-Penglai", "Yibin", "Zhangjiakou"),
        "Croatia" to listOf("Central Croatia", "Central/South Dalmatia", "Croatian Coast", "Dalmatia", "Dalmatian Interior", "Istria", "Northern Croatian Littoral", "Northern Dalmatia", "Slavonia"),
        "Cyprus" to listOf("Commandaria", "Diarizos Valley", "Krasochoria Lemesou", "Laona-Akamas", "Pitsilia", "Vouni Panagias-Ambelitis"),
        "Estonia" to listOf("Viljandi", "Võru", "West Estonian archipelago"),
        "France" to listOf("Alsace", "Bordeaux", "Burgundy", "Champagne", "Corsica", "Jura", "Languedoc-Roussillon", "Loire Valley", "Lorraine", "Madiran", "Provence", "Rhône", "Savoy"),
        "Georgia" to listOf("Abkhazia", "Imereti", "Kakheti", "Kartli", "Racha-Lechkhumi and Kvemo Svaneti"),
        "Germany" to listOf("Ahr", "Baden", "Franconia", "Hessische Bergstraße", "Mittelrhein", "Mosel", "Nahe", "Palatinate", "Rheingau", "Rheinhessen", "Saale-Unstrut", "Saxony", "Württemberg"),
        "Greece" to listOf("Aegean Islands", "Central Greece", "Ionian Islands", "Macedonia", "Peloponnesus"),
        "Hungary" to listOf("Balaton/Badacsony", "Eger", "Mátra", "Somló", "Sopron", "Szekszárd", "Tokaj", "Villány"),
        "India" to listOf("Bangalore", "Bijapur", "Narayangaon", "Nashik", "Pune", "Sangli"),
        "Indonesia" to listOf("North Bali"),
        "Israel" to listOf("Galilee", "Golan Heights", "Judean Hills", "Mount Carmel", "Negev", "Rishon LeZion"),
        "Italy" to listOf("Abruzzo", "Apulia", "Calabria", "Campania", "Emilia-Romagna", "Liguria", "Lombardy", "Marche", "Piedmont", "Sardinia", "Sicily", "Trentino-Alto Adige", "Tuscany", "Umbria", "Veneto"),
        "Latvia" to listOf("Courland", "Sabile", "Semigallia"),
        "Lithuania" to listOf("Anykščių vynas", "Mėmelio vynas"),
        "Mexico" to listOf("Aguascalientes", "Baja California", "Coahuila/Durango", "Guanajuato", "Hidalgo", "Nuevo León", "Querétaro", "Valle de Sonora", "Valle de Tequisquiapan", "Zacatecas"),
        "New Zealand" to listOf("Auckland", "Canterbury", "Central Otago", "Gisborne", "Hawke's Bay", "Marlborough", "Nelson", "Northland", "Waikato", "Wairarapa", "Waitaki Valley"),
        "Norway" to listOf("Sognefjord", "Viken/Vestfold og Telemark"),
        "Peru" to listOf("Arequipa", "Ica Region", "Lima Region"),
        "Portugal" to listOf("Alentejo", "Bairrada", "Bucelas", "Carcavelos", "Colares", "Dão", "Lagoa", "Lagos", "Madeira", "Portimão", "Porto e Douro", "Setúbal", "Tavira", "Vinhos Verdes"),
        "Romania" to listOf("Banat", "Crișana", "Dobrogea", "Moldavia", "Muntenia", "Oltenia", "Transylvania"),
        "Serbia" to listOf("Banat", "Nišava-South Morava", "Pocerina", "Srem", "Subotica-Horgoš", "Timok Valley", "West Morava", "Šumadija-Great Morava"),
        "Slovakia" to listOf("Južnoslovenská", "Malokarpatská", "Nitrianska", "Stredoslovenská", "Tokaj", "Východoslovenská", "southern Slovakia"),
        "South Africa" to listOf("Breede River Valley", "Constantia", "Durbanville", "Elgin", "Elim", "Franschhoek", "Little Karoo", "Orange River Valley", "Paarl", "Robertson", "Stellenbosch", "Swartland", "Tulbagh"),
        "Spain" to listOf("Andalusia", "Aragon", "Balearic Islands", "Basque Country", "Canary Islands", "Castile and León", "Castile–La Mancha", "Catalonia", "Community of Madrid", "Extremadura", "Galicia", "La Rioja", "Navarre", "Región de Murcia", "Valencian Community"),
        "Sweden" to listOf("Gutevin"),
        "Switzerland" to listOf("Aargau", "Bern", "Freiburg", "Geneva", "Grisons", "Neuchâtel", "Schaffhausen", "St. Gallen", "Thurgau", "Ticino", "Valais", "Vaud", "Zürich"),
        "Turkey" to listOf("Ankara area", "Elazığ/Diyarbakır", "Kırklareli", "Marmara/Avşa Island", "Tekirdağ/Bozcaada", "central Anatolia/eastern Aegean", "southeastern Anatolia", "Çal/Denizli", "Çanakkale"),
        "Ukraine" to listOf("Autonomous Republic of Crimea & Sevastopol", "Kherson Oblast", "Mykolaiv Oblast", "Odesa Oblast", "Zakarpattia Oblast", "Zaporizhzhia Oblast"),
        "United Kingdom" to listOf("Hampshire", "Kent", "Surrey", "Sussex"),
        "United States" to listOf("Arizona", "California", "Colorado", "Idaho", "Michigan", "Missouri", "New Jersey", "New Mexico", "New York", "Oregon", "Pennsylvania", "Texas", "Virginia", "Washington"),
        "Uruguay" to listOf("Canelones", "Colonia", "Maldonado", "Montevideo", "San José"),
        "Venezuela" to listOf("Carora", "Lara State"),
    )
}
