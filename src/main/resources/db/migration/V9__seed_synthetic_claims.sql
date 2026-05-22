-- Synthetic seed data for analytics dashboard testing

-- ── 5 clients ────────────────────────────────────────────────────────────────
INSERT INTO clients (id, full_name, email, phone, national_id) VALUES
  ('a0000001-0000-0000-0000-000000000001', 'Mohamed Ben Ali',    'mohamed.benali.seed@mail.tn',    '+21698112233', 'SYN00000001'),
  ('a0000001-0000-0000-0000-000000000002', 'Fatma Trabelsi',     'fatma.trabelsi.seed@mail.tn',    '+21622334455', 'SYN00000002'),
  ('a0000001-0000-0000-0000-000000000003', 'Karim Jendoubi',     'karim.jendoubi.seed@mail.tn',    '+21655667788', 'SYN00000003'),
  ('a0000001-0000-0000-0000-000000000004', 'Sonia Gharbi',       'sonia.gharbi.seed@mail.tn',      '+21699001122', 'SYN00000004'),
  ('a0000001-0000-0000-0000-000000000005', 'Nabil Chaouachi',    'nabil.chaouachi.seed@mail.tn',   '+21677889900', 'SYN00000005')
ON CONFLICT DO NOTHING;

-- ── 5 policies ───────────────────────────────────────────────────────────────
INSERT INTO policies (id, client_id, policy_number, type, coverage_limit, deductible, start_date, end_date) VALUES
  ('b0000001-0000-0000-0000-000000000001', 'a0000001-0000-0000-0000-000000000001', 'SYN-POL-001', 'VEHICLE_DAMAGE',   50000.00, 500.00, '2023-01-01', '2027-12-31'),
  ('b0000001-0000-0000-0000-000000000002', 'a0000001-0000-0000-0000-000000000002', 'SYN-POL-002', 'THEFT',            30000.00, 300.00, '2023-01-01', '2027-12-31'),
  ('b0000001-0000-0000-0000-000000000003', 'a0000001-0000-0000-0000-000000000003', 'SYN-POL-003', 'PROPERTY_DAMAGE',  40000.00, 400.00, '2023-01-01', '2027-12-31'),
  ('b0000001-0000-0000-0000-000000000004', 'a0000001-0000-0000-0000-000000000004', 'SYN-POL-004', 'VEHICLE_DAMAGE',   60000.00, 600.00, '2023-01-01', '2027-12-31'),
  ('b0000001-0000-0000-0000-000000000005', 'a0000001-0000-0000-0000-000000000005', 'SYN-POL-005', 'THEFT',            25000.00, 250.00, '2023-01-01', '2027-12-31')
ON CONFLICT DO NOTHING;

-- ── 100 claims ───────────────────────────────────────────────────────────────
-- Types   : VEHICLE_DAMAGE=45  THEFT=30  PROPERTY_DAMAGE=25
-- Statuses: APPROVED=60  PENDING_REVIEW=20  REJECTED=20
-- confidence_score ≥ 0.6 → exactly 15 REJECTED claims → fraudFlaggedClaims=15
-- Dates   : 2024-01 to 2025-12  (historical, for monthly chart)

INSERT INTO claims
  (client_id, policy_id, type, status, description, estimated_cost,
   confidence_score, rejection_reason, submitted_at, updated_at)
VALUES

-- ═══ VEHICLE_DAMAGE — APPROVED (27) ═══════════════════════════════════════

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Collision frontale sur autoroute A1 : pare-choc avant et capot endommagés.',
 4200.00, 0.08, NULL, '2026-03-01'::timestamptz, '2026-03-01'::timestamptz + interval '76 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Accrochage parking : portière avant droite enfoncée, rétroviseur cassé.',
 1800.00, 0.12, NULL, '2026-03-02'::timestamptz, '2026-03-02'::timestamptz + interval '81 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Sortie de route sur chaussée mouillée : aile avant gauche froissée, pare-brise fissuré.',
 2900.00, 0.10, NULL, '2026-03-03'::timestamptz, '2026-03-03'::timestamptz + interval '74 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Tamponnement arrière au feu rouge : pare-choc arrière enfoncé, coffre déformé.',
 2200.00, 0.09, NULL, '2026-03-04'::timestamptz, '2026-03-04'::timestamptz + interval '78 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Grêle violente : bosses sur capot, toit et carrosserie.',
 3500.00, 0.05, NULL, '2026-03-05'::timestamptz, '2026-03-05'::timestamptz + interval '72 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Choc latéral droit en intersection : portière arrière droite abîmée.',
 2600.00, 0.11, NULL, '2026-03-07'::timestamptz, '2026-03-07'::timestamptz + interval '83 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Accrochage ruelle étroite : pare-choc avant rayé, phare avant fissuré.',
 1400.00, 0.07, NULL, '2026-03-08'::timestamptz, '2026-03-08'::timestamptz + interval '69 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Collision avec poteau : aile avant droite déformée, capot plié.',
 3100.00, 0.13, NULL, '2026-03-09'::timestamptz, '2026-03-09'::timestamptz + interval '80 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Choc frontal violent : moteur et avant du véhicule endommagés.',
 8500.00, 0.17, NULL, '2026-03-10'::timestamptz, '2026-03-10'::timestamptz + interval '88 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Pare-brise fissuré par caillou projeté sur autoroute.',
 900.00,  0.04, NULL, '2026-03-11'::timestamptz, '2026-03-11'::timestamptz + interval '70 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Collision arrière : pare-choc arrière, hayon et feux détruits.',
 3400.00, 0.10, NULL, '2026-03-12'::timestamptz, '2026-03-12'::timestamptz + interval '79 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Accrochage côté conducteur : portière avant gauche rayée.',
 1200.00, 0.09, NULL, '2026-03-13'::timestamptz, '2026-03-13'::timestamptz + interval '75 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Arbre tombé sur véhicule pendant tempête : toit enfoncé, pare-brise brisé.',
 5200.00, 0.08, NULL, '2026-03-14'::timestamptz, '2026-03-14'::timestamptz + interval '84 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Sortie de route dans virage : pare-choc avant et jante endommagés.',
 3000.00, 0.12, NULL, '2026-03-16'::timestamptz, '2026-03-16'::timestamptz + interval '77 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Véhicule rayé sur tout le flanc gauche dans une rue commerçante.',
 800.00,  0.07, NULL, '2026-03-17'::timestamptz, '2026-03-17'::timestamptz + interval '71 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Inondation moteur suite aux pluies torrentielles de Nabeul.',
 6500.00, 0.10, NULL, '2026-03-18'::timestamptz, '2026-03-18'::timestamptz + interval '82 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Tamponnement au carrefour : portières droites et aile endommagées.',
 2800.00, 0.10, NULL, '2026-03-19'::timestamptz, '2026-03-19'::timestamptz + interval '78 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Choc frontal sur camion : capot, pare-choc avant et phares cassés.',
 3700.00, 0.09, NULL, '2026-03-20'::timestamptz, '2026-03-20'::timestamptz + interval '80 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Collision légère sortie de garage : pare-choc avant légèrement enfoncé.',
 700.00,  0.05, NULL, '2026-03-21'::timestamptz, '2026-03-21'::timestamptz + interval '68 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Choc arrière à l''arrêt : pare-choc arrière et malle enfoncés.',
 1900.00, 0.08, NULL, '2026-03-22'::timestamptz, '2026-03-22'::timestamptz + interval '73 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Accrochage voie rapide : aile avant droite enfoncée.',
 2400.00, 0.11, NULL, '2026-03-23'::timestamptz, '2026-03-23'::timestamptz + interval '76 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Accident de nuit avec animal : capot et radiateur endommagés.',
 2700.00, 0.09, NULL, '2026-03-25'::timestamptz, '2026-03-25'::timestamptz + interval '79 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Rayures profondes flanc droit suite à accrochage dans ruelle.',
 1300.00, 0.06, NULL, '2026-03-26'::timestamptz, '2026-03-26'::timestamptz + interval '72 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Choc frontal modéré au feu rouge : pare-choc avant et calandre.',
 1600.00, 0.06, NULL, '2026-03-27'::timestamptz, '2026-03-27'::timestamptz + interval '74 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Grêle sur capot et ailes : multiples impacts, peinture abîmée.',
 2100.00, 0.06, NULL, '2026-03-28'::timestamptz, '2026-03-28'::timestamptz + interval '77 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','APPROVED','Véhicule renversé sur verglas : toit enfoncé, vitres brisées.',
 9800.00, 0.15, NULL, '2026-03-29'::timestamptz, '2026-03-29'::timestamptz + interval '90 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','APPROVED','Aile arrière gauche enfoncée par autre conducteur en parking.',
 1100.00, 0.08, NULL, '2026-03-30'::timestamptz, '2026-03-30'::timestamptz + interval '73 seconds'),

-- ═══ VEHICLE_DAMAGE — PENDING_REVIEW (9) ══════════════════════════════════

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Sinistre allégué déplacement pro : aucun témoin, dommages multiples.',
 3800.00, 0.38, NULL, '2026-03-31'::timestamptz, '2026-03-31'::timestamptz + interval '32 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Véhicule endommagé sur parking privé, devis très élevé.',
 7200.00, 0.45, NULL, '2026-04-01'::timestamptz, '2026-04-01'::timestamptz + interval '35 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Choc frontal avec bus : moteur et avant suspects, expertise requise.',
 16200.00,0.32, NULL, '2026-04-03'::timestamptz, '2026-04-03'::timestamptz + interval '30 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Carambolage sur A1 : carrosserie latérale gauche très endommagée.',
 9400.00, 0.41, NULL, '2026-04-04'::timestamptz, '2026-04-04'::timestamptz + interval '33 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Véhicule retrouvé vandalisé : état général dégradé, dossier incomplet.',
 5100.00, 0.35, NULL, '2026-04-05'::timestamptz, '2026-04-05'::timestamptz + interval '28 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Collision multi-éléments : capot, phares, radiateur — expertise nécessaire.',
 12800.00,0.28, NULL, '2026-04-06'::timestamptz, '2026-04-06'::timestamptz + interval '31 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Sortie de route à grande vitesse, dommages structurels importants.',
 17500.00,0.52, NULL, '2026-04-07'::timestamptz, '2026-04-07'::timestamptz + interval '36 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Accrochage en zone non couverte géographiquement, dossier incomplet.',
 2300.00, 0.25, NULL, '2026-04-08'::timestamptz, '2026-04-08'::timestamptz + interval '29 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','PENDING_REVIEW','Accrochage grande vitesse A3, dommages étendus flanc droit.',
 11500.00,0.47, NULL, '2026-04-09'::timestamptz, '2026-04-09'::timestamptz + interval '34 seconds'),

-- ═══ VEHICLE_DAMAGE — REJECTED (9) ════════════════════════════════════════
-- 3 non-fraud (score 0.23–0.38), 4 fraud-high (0.62–0.78), 2 confirmed (0.83–0.91)

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','REJECTED','Sinistre déclaré hors délai contractuel de 5 jours.',
 4500.00, 0.23,'Sinistre déclaré hors délai contractuel.','2026-04-10'::timestamptz,'2026-04-10'::timestamptz + interval '68 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','REJECTED','Contrat expiré à la date du sinistre.',
 6800.00, 0.31,'Contrat expiré à la date du sinistre.','2026-04-11'::timestamptz,'2026-04-11'::timestamptz + interval '65 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','REJECTED','Conducteur non désigné au contrat lors du sinistre.',
 3600.00, 0.38,'Conducteur non autorisé : exclusion contractuelle.','2026-04-13'::timestamptz,'2026-04-13'::timestamptz + interval '70 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','REJECTED','Photos fournies ne correspondent pas au véhicule assuré, VIN différent.',
 5200.00, 0.72,'Fraude identifiée : photos d''un véhicule tiers, VIN incohérent.','2026-04-14'::timestamptz,'2026-04-14'::timestamptz + interval '82 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','REJECTED','Dommages préexistants non liés à l''incident déclaré.',
 4100.00, 0.67,'Dommages préexistants constatés par expert indépendant.','2026-04-15'::timestamptz,'2026-04-15'::timestamptz + interval '78 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','REJECTED','Incohérence majeure entre description et dommages constatés.',
 4800.00, 0.76,'Fraude présumée : devis gonflé, expertise contradictoire.','2026-04-16'::timestamptz,'2026-04-16'::timestamptz + interval '85 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','REJECTED','Tentative de double facturation de pièces détachées.',
 7700.00, 0.63,'Fraude : double soumission de factures identiques.','2026-04-17'::timestamptz,'2026-04-17'::timestamptz + interval '80 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000004',
 'VEHICLE_DAMAGE','REJECTED','Véhicule déjà déclaré sinistré pour les mêmes dommages.',
 5500.00, 0.91,'Double assurance non déclarée : sinistre déjà indemnisé.','2026-04-18'::timestamptz,'2026-04-18'::timestamptz + interval '88 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000001',
 'VEHICLE_DAMAGE','REJECTED','Montant réclamé trois fois supérieur au devis indépendant.',
 8900.00, 0.83,'Fraude prouvée : surfacturation confirmée par expert.','2026-04-19'::timestamptz,'2026-04-19'::timestamptz + interval '86 seconds'),

-- ═══ THEFT — APPROVED (18) ════════════════════════════════════════════════

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Vol de smartphone iPhone 14 dans un café à Tunis.',
 3100.00, 0.09, NULL, '2026-04-20'::timestamptz, '2026-04-20'::timestamptz + interval '75 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Vol de sac contenant portefeuille, clés et smartphone Samsung S23.',
 2900.00, 0.11, NULL, '2026-04-22'::timestamptz, '2026-04-22'::timestamptz + interval '78 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Effraction véhicule : laptop MacBook Pro volé siège arrière.',
 8500.00, 0.14, NULL, '2026-04-23'::timestamptz, '2026-04-23'::timestamptz + interval '82 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Vol de moto 125cc garée dans la rue.',
 3800.00, 0.10, NULL, '2026-04-24'::timestamptz, '2026-04-24'::timestamptz + interval '74 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Cambriolage domicile : TV, ordinateur portable et bijoux volés.',
 5200.00, 0.08, NULL, '2026-04-25'::timestamptz, '2026-04-25'::timestamptz + interval '71 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Vol de vélo électrique depuis couloir de l''immeuble.',
 2400.00, 0.07, NULL, '2026-04-26'::timestamptz, '2026-04-26'::timestamptz + interval '69 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Smartphone Xiaomi Redmi Note 12 volé dans le bus.',
 900.00,  0.06, NULL, '2026-04-27'::timestamptz, '2026-04-27'::timestamptz + interval '70 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Cambriolage avec effraction : réfrigérateur, machine à laver, TV emportés.',
 4800.00, 0.09, NULL, '2026-04-28'::timestamptz, '2026-04-28'::timestamptz + interval '76 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Vol de PC gaming (ASUS ROG) dans bureau pendant conférence.',
 3800.00, 0.12, NULL, '2026-04-29'::timestamptz, '2026-04-29'::timestamptz + interval '79 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Effraction voiture : sac à dos et iPad volés.',
 2100.00, 0.10, NULL, '2026-05-01'::timestamptz, '2026-05-01'::timestamptz + interval '73 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Vol montre de luxe dans vestiaire salle de sport.',
 1800.00, 0.08, NULL, '2026-05-02'::timestamptz, '2026-05-02'::timestamptz + interval '72 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Smartphone Samsung Galaxy S24 arraché dans la rue.',
 3250.00, 0.10, NULL, '2026-05-03'::timestamptz, '2026-05-03'::timestamptz + interval '77 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Cambriolage appartement : bijoux en or, espèces et smartphone.',
 6500.00, 0.12, NULL, '2026-05-04'::timestamptz, '2026-05-04'::timestamptz + interval '81 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Vol iPhone 15 Pro sur table de restaurant.',
 4200.00, 0.08, NULL, '2026-05-05'::timestamptz, '2026-05-05'::timestamptz + interval '74 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Vélo électrique volé depuis garage immeuble résidentiel.',
 2600.00, 0.06, NULL, '2026-05-06'::timestamptz, '2026-05-06'::timestamptz + interval '70 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','Vol matériel informatique professionnel : laptop + disque dur externe.',
 5100.00, 0.11, NULL, '2026-05-07'::timestamptz, '2026-05-07'::timestamptz + interval '78 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000002',
 'THEFT','APPROVED','Moto 50cc volée en plein jour devant domicile.',
 2300.00, 0.07, NULL, '2026-05-08'::timestamptz, '2026-05-08'::timestamptz + interval '73 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000005',
 'THEFT','APPROVED','PC bureau Core i5 volé lors d''un déménagement.',
 1900.00, 0.09, NULL, '2026-05-10'::timestamptz, '2026-05-10'::timestamptz + interval '75 seconds'),

-- ═══ THEFT — PENDING_REVIEW (6) ═══════════════════════════════════════════

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000002',
 'THEFT','PENDING_REVIEW','Vol de bijoux déclaré tardivement, aucune preuve de propriété.',
 4500.00, 0.42, NULL, '2026-05-11'::timestamptz, '2026-05-11'::timestamptz + interval '30 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000005',
 'THEFT','PENDING_REVIEW','Vol moto 250cc, pas de plainte déposée, valeur déclarée suspecte.',
 9000.00, 0.48, NULL, '2026-05-12'::timestamptz, '2026-05-12'::timestamptz + interval '33 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000002',
 'THEFT','PENDING_REVIEW','Cambriolage allégué sans traces d''effraction, montant très élevé.',
 12500.00,0.55, NULL, '2026-05-13'::timestamptz, '2026-05-13'::timestamptz + interval '35 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000005',
 'THEFT','PENDING_REVIEW','Vol smartphone déclaré deux semaines après l''incident.',
 3100.00, 0.38, NULL, '2026-05-14'::timestamptz, '2026-05-14'::timestamptz + interval '29 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000002',
 'THEFT','PENDING_REVIEW','Vol PC gaming : facture d''achat incohérente avec modèle déclaré.',
 4800.00, 0.51, NULL, '2026-05-15'::timestamptz, '2026-05-15'::timestamptz + interval '32 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000005',
 'THEFT','PENDING_REVIEW','Moto retrouvée endommagée en décharge, dossier ambigu.',
 4000.00, 0.44, NULL, '2026-05-16'::timestamptz, '2026-05-16'::timestamptz + interval '31 seconds'),

-- ═══ THEFT — REJECTED (6) — all fraud (score 0.64–0.94) ═══════════════════

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000002',
 'THEFT','REJECTED','CCTV du voisinage montre propriétaire avec l''objet après date déclarée.',
 3100.00, 0.78,'Fraude : enregistrement vidéo contredit la déclaration de vol.','2026-05-17'::timestamptz,'2026-05-17'::timestamptz + interval '83 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000005',
 'THEFT','REJECTED','Objet déclaré volé retrouvé en vente sur Tayara.tn au nom de l''assuré.',
 3800.00, 0.86,'Fraude prouvée : objet retrouvé en revente par l''assuré.','2026-05-18'::timestamptz,'2026-05-18'::timestamptz + interval '87 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000002',
 'THEFT','REJECTED','Valeur bijoux déclarée 8× supérieure à l''expertise indépendante.',
 8000.00, 0.69,'Fraude : valeur déclarée largement gonflée.','2026-05-20'::timestamptz,'2026-05-20'::timestamptz + interval '81 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000005',
 'THEFT','REJECTED','Moto vendue avant la date de vol déclarée selon transfert de propriété.',
 3800.00, 0.94,'Fraude : moto vendue avant la date de vol déclarée.','2026-05-21'::timestamptz,'2026-05-21'::timestamptz + interval '90 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000002',
 'THEFT','REJECTED','Contrat souscrit 3 jours avant déclaration de vol, délai de carence non respecté.',
 7400.00, 0.64,'Fraude présumée : délai de carence non expiré.','2026-05-22'::timestamptz,'2026-05-22'::timestamptz + interval '79 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000005',
 'THEFT','REJECTED','Smartphone déclaré volé mais retrouvé chez proche de l''assuré.',
 2700.00, 0.73,'Fraude identifiée : smartphone retrouvé chez entourage.','2026-05-23'::timestamptz,'2026-05-23'::timestamptz + interval '82 seconds'),

-- ═══ PROPERTY_DAMAGE — APPROVED (15) ══════════════════════════════════════

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Dégât des eaux : fuite voisin du dessus, plafond et murs salon inondés.',
 2800.00, 0.07, NULL, '2026-05-24'::timestamptz, '2026-05-24'::timestamptz + interval '77 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Bris de vitre : fenêtre séjour cassée par balle perdue.',
 450.00,  0.05, NULL, '2026-05-25'::timestamptz, '2026-05-25'::timestamptz + interval '68 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Vandalisme : façade commerce dégradée, vitrines brisées.',
 3200.00, 0.09, NULL, '2026-05-26'::timestamptz, '2026-05-26'::timestamptz + interval '74 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Incendie cuisine : électroménager et meubles détruits, feu maîtrisé.',
 8500.00, 0.11, NULL, '2026-05-27'::timestamptz, '2026-05-27'::timestamptz + interval '83 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Grêle : toiture villa partiellement endommagée, gouttières arrachées.',
 3800.00, 0.06, NULL, '2026-05-29'::timestamptz, '2026-05-29'::timestamptz + interval '71 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Tempête : arbre tombé sur clôture et jardin.',
 1200.00, 0.08, NULL, '2026-05-30'::timestamptz, '2026-05-30'::timestamptz + interval '70 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Infiltration toiture : appartement entier touché par dégât des eaux.',
 7500.00, 0.10, NULL, '2026-05-31'::timestamptz, '2026-05-31'::timestamptz + interval '79 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Effondrement partiel mur de soutènement suite aux pluies.',
 4200.00, 0.09, NULL, '2026-06-01'::timestamptz, '2026-06-01'::timestamptz + interval '76 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Incendie dépendance : garage brûlé partiellement.',
 5100.00, 0.12, NULL, '2026-06-02'::timestamptz, '2026-06-02'::timestamptz + interval '81 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Vandalisme véhicule stationné : carrosserie rayée, rétroviseurs brisés.',
 1100.00, 0.07, NULL, '2026-06-03'::timestamptz, '2026-06-03'::timestamptz + interval '72 seconds'),

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Grêle intensive : bris de tuiles, velux cassé, infiltrations.',
 4600.00, 0.09, NULL, '2026-06-04'::timestamptz, '2026-06-04'::timestamptz + interval '74 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Fuite importante : murs et sol chambre principale endommagés.',
 3100.00, 0.07, NULL, '2026-06-05'::timestamptz, '2026-06-05'::timestamptz + interval '73 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Inondation locale commerciale : rupture canalisation externe.',
 9200.00, 0.08, NULL, '2026-06-07'::timestamptz, '2026-06-07'::timestamptz + interval '80 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Incendie partiel salon : court-circuit électrique, mobilier endommagé.',
 6800.00, 0.10, NULL, '2026-06-08'::timestamptz, '2026-06-08'::timestamptz + interval '78 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','APPROVED','Bris porte vitrée double vitrage entrée principale du commerce.',
 750.00,  0.05, NULL, '2026-06-09'::timestamptz, '2026-06-09'::timestamptz + interval '69 seconds'),

-- ═══ PROPERTY_DAMAGE — PENDING_REVIEW (5) ═════════════════════════════════

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','PENDING_REVIEW','Incendie allégué entrepôt : montant très élevé, cause indéterminée.',
 48000.00,0.48, NULL, '2026-06-10'::timestamptz, '2026-06-10'::timestamptz + interval '34 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','PENDING_REVIEW','Dégât des eaux appartement complet, relation propriétaire/locataire floue.',
 11000.00,0.39, NULL, '2026-06-11'::timestamptz, '2026-06-11'::timestamptz + interval '31 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','PENDING_REVIEW','Inondation tempête : montant réclamé inclut équipements anciens à neuf.',
 15500.00,0.43, NULL, '2026-06-12'::timestamptz, '2026-06-12'::timestamptz + interval '33 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','PENDING_REVIEW','Séisme : fissures importantes, expertise structurelle nécessaire.',
 22000.00,0.31, NULL, '2026-06-13'::timestamptz, '2026-06-13'::timestamptz + interval '30 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','PENDING_REVIEW','Incendie commerce : cause suspectée volontaire selon voisins.',
 35000.00,0.57, NULL, '2026-06-14'::timestamptz, '2026-06-14'::timestamptz + interval '36 seconds'),

-- ═══ PROPERTY_DAMAGE — REJECTED (5) ═══════════════════════════════════════
-- 2 non-fraud (score 0.29–0.34), 2 fraud-high (0.65–0.74), 1 confirmed (0.89)

('a0000001-0000-0000-0000-000000000001','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','REJECTED','Dommages existants avant souscription du contrat, non déclarés.',
 5400.00, 0.29,'Dommages préexistants non déclarés lors de la visite d''état des lieux.','2026-06-16'::timestamptz,'2026-06-16'::timestamptz + interval '66 seconds'),

('a0000001-0000-0000-0000-000000000002','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','REJECTED','Contrat habitation ne couvre pas les dépendances séparées.',
 8200.00, 0.34,'Dépendances séparées non incluses dans les garanties contractuelles.','2026-06-17'::timestamptz,'2026-06-17'::timestamptz + interval '67 seconds'),

('a0000001-0000-0000-0000-000000000003','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','REJECTED','Double déclaration du même sinistre sous deux contrats différents.',
 18500.00,0.74,'Double déclaration détectée : sinistre déjà indemnisé par autre contrat.','2026-06-18'::timestamptz,'2026-06-18'::timestamptz + interval '84 seconds'),

('a0000001-0000-0000-0000-000000000004','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','REJECTED','Incendie commercial : rapport pompiers confirme cause volontaire.',
 28000.00,0.89,'Incendie volontaire confirmé par rapport officiel des pompiers.','2026-06-19'::timestamptz,'2026-06-19'::timestamptz + interval '91 seconds'),

('a0000001-0000-0000-0000-000000000005','b0000001-0000-0000-0000-000000000003',
 'PROPERTY_DAMAGE','REJECTED','Dégât allégué résulte de travaux défectueux non couverts.',
 4100.00, 0.65,'Dommage résultant de travaux défectueux, non assimilable à sinistre accidentel.','2026-06-20'::timestamptz,'2026-06-20'::timestamptz + interval '76 seconds');
