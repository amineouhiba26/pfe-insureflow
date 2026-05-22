CREATE TABLE IF NOT EXISTS non_vehicle_prices (
    id               SERIAL PRIMARY KEY,
    claim_type       VARCHAR(50)    NOT NULL,
    category         VARCHAR(100)   NOT NULL,
    item_description VARCHAR(200),
    min_price_tnd    DECIMAL(10,2)  NOT NULL,
    avg_price_tnd    DECIMAL(10,2)  NOT NULL,
    max_price_tnd    DECIMAL(10,2)  NOT NULL,
    source           VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_nvp_claim_type ON non_vehicle_prices (claim_type);
CREATE INDEX IF NOT EXISTS idx_nvp_category   ON non_vehicle_prices (category);

INSERT INTO non_vehicle_prices (claim_type, category, item_description, min_price_tnd, avg_price_tnd, max_price_tnd, source) VALUES

-- ─── THEFT (15 entrées) ────────────────────────────────────────────────────
('THEFT', 'smartphone',         'iPhone 14 (128 Go) — vol / disparition',                2800.00,  3100.00,  3500.00, 'référence_marché_tunisien_2025'),
('THEFT', 'smartphone',         'iPhone 15 (128 Go) — vol / disparition',                3400.00,  3800.00,  4200.00, 'référence_marché_tunisien_2025'),
('THEFT', 'smartphone',         'Samsung Galaxy S23 — vol / disparition',                2400.00,  2700.00,  3000.00, 'référence_marché_tunisien_2025'),
('THEFT', 'smartphone',         'Samsung Galaxy S24 — vol / disparition',                2900.00,  3250.00,  3700.00, 'référence_marché_tunisien_2025'),
('THEFT', 'smartphone',         'Xiaomi Redmi Note 12 (milieu de gamme) — vol',           700.00,   900.00,  1100.00, 'référence_marché_tunisien_2025'),
('THEFT', 'laptop',             'MacBook Pro M2 13 pouces — vol / disparition',          7500.00,  8500.00,  9500.00, 'référence_marché_tunisien_2025'),
('THEFT', 'laptop',             'PC gaming ASUS/Lenovo (RTX 3060) — vol',                3200.00,  3800.00,  4500.00, 'référence_marché_tunisien_2025'),
('THEFT', 'laptop',             'PC bureau mid-range (Core i5, 16 Go) — vol',            1500.00,  1900.00,  2400.00, 'référence_marché_tunisien_2025'),
('THEFT', 'moto',               'Moto 50cc (scooter) — vol total',                       1800.00,  2300.00,  2800.00, 'référence_marché_tunisien_2025'),
('THEFT', 'moto',               'Moto 125cc — vol total',                                3000.00,  3800.00,  4800.00, 'référence_marché_tunisien_2025'),
('THEFT', 'moto',               'Moto 250cc — vol total',                                5500.00,  7000.00,  9000.00, 'référence_marché_tunisien_2025'),
('THEFT', 'vélo électrique',    'Vélo électrique (autonomie 40-60 km) — vol',            1800.00,  2400.00,  3200.00, 'référence_marché_tunisien_2025'),
('THEFT', 'électroménager',     'Téléviseur LED 55 pouces Samsung/LG — vol',              950.00,  1200.00,  1600.00, 'référence_marché_tunisien_2025'),
('THEFT', 'électroménager',     'Réfrigérateur congélateur 300 L — vol',                  950.00,  1300.00,  1800.00, 'référence_marché_tunisien_2025'),
('THEFT', 'bijoux et montres',  'Bijoux en or 18 carats / montre de valeur — vol',       1500.00,  3500.00,  8000.00, 'référence_marché_tunisien_2025'),

-- ─── PROPERTY_DAMAGE (15 entrées) ─────────────────────────────────────────
('PROPERTY_DAMAGE', 'bris de glace',    'Fenêtre standard simple vitrage — bris',           180.00,   280.00,   450.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'bris de glace',    'Porte vitrée double vitrage — bris',               350.00,   500.00,   750.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'bris de glace',    'Véranda / verrière — bris partiel',                600.00,   950.00,  1500.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'dégât des eaux',   'Dégâts des eaux — plafond seul',                   400.00,   650.00,  1000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'dégât des eaux',   'Dégâts des eaux — murs et sol',                    900.00,  1500.00,  2800.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'dégât des eaux',   'Dégâts des eaux — appartement complet',           2500.00,  4500.00,  8000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'incendie',         'Incendie partiel — dommages superficiels',         1200.00,  2500.00,  5000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'incendie',         'Incendie — appareils électroniques détruits',       800.00,  1800.00,  4000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'incendie',         'Incendie — mobilier complet détruit',              3000.00,  6000.00, 12000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'vandalisme',       'Vandalisme — façade dégradée / graffitis',          250.00,   500.00,  1000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'vandalisme',       'Vandalisme — véhicule stationné (carrosserie)',      300.00,   600.00,  1200.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'effondrement',     'Effondrement mur partiel — réparation',             800.00,  1500.00,  3000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'effondrement',     'Effondrement toiture partielle',                   1500.00,  3500.00,  7000.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'inondation',       'Inondation intérieure — habitation',               1800.00,  3500.00,  7500.00, 'référence_marché_tunisien_2025'),
('PROPERTY_DAMAGE', 'inondation',       'Inondation — local commercial',                    3000.00,  6000.00, 14000.00, 'référence_marché_tunisien_2025'),

-- ─── NATURAL_DISASTER (10 entrées) ────────────────────────────────────────
('NATURAL_DISASTER', 'inondation',      'Véhicule immergé — inondation catastrophique',     4000.00,  7000.00, 15000.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'inondation',      'Habitation inondée — dommages intérieurs',         2500.00,  5000.00, 11000.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'inondation',      'Local commercial inondé',                          4000.00,  8000.00, 18000.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'grêle',           'Grêle — dommages carrosserie véhicule',             600.00,  1200.00,  3000.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'grêle',           'Grêle — toiture habitation',                        500.00,  1000.00,  2500.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'grêle',           'Grêle — cultures agricoles (par hectare)',          1500.00,  3000.00,  6000.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'tempête',         'Tempête — toiture emportée / endommagée',           900.00,  2000.00,  5000.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'tempête',         'Tempête — façade et menuiseries',                   400.00,   900.00,  2000.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'tempête',         'Tempête — mobilier jardin / clôture',               150.00,   350.00,   700.00, 'référence_marché_tunisien_2025'),
('NATURAL_DISASTER', 'séisme',          'Séisme — fissures structurelles légères',           500.00,  1500.00,  4000.00, 'référence_marché_tunisien_2025'),

-- ─── HEALTH (5 entrées) ───────────────────────────────────────────────────
('HEALTH', 'hospitalisation',   'Hospitalisation — coût par jour (chambre + soins)',       200.00,   350.00,   600.00, 'référence_marché_tunisien_2025'),
('HEALTH', 'chirurgie',         'Chirurgie mineure (ambulatoire, anesthésie locale)',       500.00,   900.00,  1800.00, 'référence_marché_tunisien_2025'),
('HEALTH', 'chirurgie',         'Chirurgie majeure (bloc opératoire, anesthésie générale)',1500.00,  3500.00,  8000.00, 'référence_marché_tunisien_2025'),
('HEALTH', 'soins dentaires',   'Soins dentaires — couronne / implant',                    400.00,   700.00,  1200.00, 'référence_marché_tunisien_2025'),
('HEALTH', 'optique',           'Lunettes de vue (monture + verres correcteurs)',           180.00,   320.00,   600.00, 'référence_marché_tunisien_2025');
