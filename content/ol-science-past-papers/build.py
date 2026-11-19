#!/usr/bin/env python3
"""Builds the import file for the G.C.E. O/L Science Paper I questions of the 2024(2025) and 2025(2026) examinations.

Source: the English-medium Paper I question papers (Department of Examinations, Sri Lanka) and the official
answer keys supplied by the client. Explanations and key points are written for this platform.

Questions that need a picture to be answered are not in the import file; they are listed in MANUAL.md with the
figure in figures/, to be added in the question editor with the image.

Run:  python3 content/ol-science-past-papers/build.py
Out:  ol-science-past-papers.csv and .xlsx (import format of Admin → Questions → Import).
"""
import csv
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
COLUMNS = ["subject", "module", "topic", "type", "question", "option_a", "option_b", "option_c", "option_d",
           "option_e", "correct", "explanation", "key_points", "definitions", "difficulty", "seconds", "tags", "marks"]
MODULE = "Topics"  # the module the admin screens create for topics added straight under a subject
SECONDS = {"SIMPLE": 60, "MEDIUM": 90, "HARD": 120}

# Topic → subject. Topic titles are the chapter titles of the Grade 10–11 Science textbooks.
TOPICS = {
    "Biology": ["Chemical basis of life", "Structure and functions of the plant and animal cell",
                "Characteristics of organisms", "The world of life", "Continuity of life", "Inheritance",
                "Living tissues", "Photosynthesis", "Biological processes in human body", "Biosphere"],
    "Chemistry": ["Structure of matter", "Quantification of elements and compounds", "Chemical bonds",
                  "Changes in matter", "Rate of reactions", "Mixtures", "Acids, bases and salts",
                  "Heat changes associated with chemical reactions", "Electrochemistry",
                  "Hydrocarbons and their derivatives"],
    "Physics": ["Motion in a straight line", "Newton's laws of motion", "Friction", "Resultant force",
                "Hydrostatic pressure and its applications", "Current electricity", "Waves and their applications",
                "Geometrical optics", "Heat", "Power and energy of electric appliances", "Electronics",
                "Electromagnetism and electromagnetic induction"],
}
SUBJECT_OF = {t: s for s, ts in TOPICS.items() for t in ts}


def q(paper, number, topic, difficulty, question, options, answer, explanation, key_points=()):
    return dict(paper=paper, number=number, topic=topic, difficulty=difficulty, question=question,
                options=options, answer=answer, explanation=explanation, key_points=list(key_points))


P24, P25 = "2024", "2025"
QUESTIONS = [
    # ------------------------------------------------------------------------------------------- 2024(2025) Paper I
    q(P24, 1, "Living tissues", "SIMPLE",
      "To which of the following tissues does the cell type stone cells belong?",
      ["meristematic", "parenchyma", "collenchyma", "sclerenchyma"], 4,
      "Stone cells (sclereids) are dead cells with thick, lignified walls. They are one of the two cell types of "
      "sclerenchyma tissue; the other is fibres.",
      ["Sclerenchyma cells: fibres and sclereids (stone cells)", "Sclerenchyma gives mechanical strength"]),
    q(P24, 2, "Motion in a straight line", "SIMPLE",
      "Of the following pairs, the pair of vector quantities is,",
      ["force and mass.", "pressure and acceleration.", "speed and work.", "velocity and displacement."], 4,
      "A vector has both magnitude and direction. Velocity and displacement are both vectors. Mass, speed, work and "
      "pressure have magnitude only, so they are scalars.",
      ["Vectors: displacement, velocity, acceleration, force, momentum",
       "Scalars: distance, speed, mass, work, energy, pressure"]),
    q(P24, 3, "Electronics", "SIMPLE",
      "Which of the following is the element that acts as an intrinsic semiconductor at the room temperature?",
      ["Cu", "Al", "Si", "Fe"], 3,
      "Silicon (and germanium) are intrinsic semiconductors. Copper, aluminium and iron are metals, which are "
      "conductors."),
    q(P24, 4, "Structure of matter", "SIMPLE",
      "Which of the following indicates the symbols of the elements zinc, sodium and silver respectively?",
      ["Sn, S, Si", "Zn, Na, Si", "Zn, Na, Ag", "Sn, Zn, S"], 3,
      "Zinc is Zn, sodium is Na (from natrium) and silver is Ag (from argentum). Sn is tin, S is sulphur and Si is "
      "silicon."),
    q(P24, 5, "The world of life", "SIMPLE",
      "Select the option that correctly indicates the scientific name of plant mango.",
      ["Mangifera indica (not in italics)", r"\(\textit{Mangifera indica}\)", "mangifera indica (not in italics)",
       r"\(\textit{mangifera indica}\)"], 2,
      "In binomial nomenclature the genus name starts with a capital letter, the species name is all lower case, and "
      "the whole name is printed in italics (or underlined when handwritten): Mangifera indica.",
      ["Genus: capital first letter; species: lower case", "Printed in italics, underlined when handwritten"]),
    q(P24, 6, "Structure and functions of the plant and animal cell", "SIMPLE",
      "Which organelle of the cell transmits the hereditary characteristics from generation to generation?",
      ["mitochondria", "nucleus", "Golgi bodies", "ribosomes"], 2,
      "The nucleus contains the chromosomes (DNA), which carry the genes passed from parents to offspring."),
    q(P24, 7, "Continuity of life", "SIMPLE",
      "From which of the following structures in the ovaries of the female reproductive system an ovum is released?",
      ["corpus luteum", "corpus albicans", "graafian follicle", "primary follicle"], 3,
      "A primary follicle develops into a mature Graafian follicle, which bursts at ovulation to release the ovum. "
      "What is left becomes the corpus luteum, and later the corpus albicans."),
    q(P24, 8, "Hydrostatic pressure and its applications", "SIMPLE",
      "Cutting a yam with a well sharpened knife is easier. The reason for this is,",
      ["the ability to apply a greater force.", "the ability to apply a greater pressure.",
       "lessening the friction between the yam and the knife.", "lessening the weight of the knife during sharpening."],
      2,
      "Pressure = force ÷ area. Sharpening makes the contact area of the edge very small, so the same force produces "
      "a much greater pressure.",
      ["Pressure = force ÷ area", "Smaller area, same force: greater pressure"]),
    q(P24, 9, "Structure of matter", "MEDIUM",
      "The formula of the compound formed by the element X with oxygen is XO while the formula of the compound formed "
      "by the element Y with sodium is NaY. What is the formula of the compound formed by the combination of X and Y?",
      [r"\(XY\)", r"\(X_2Y\)", r"\(XY_2\)", r"\(XY_4\)"], 3,
      "In XO, oxygen has a valency of 2, so X has a valency of 2. In NaY, sodium has a valency of 1, so Y has a "
      "valency of 1. X (2) and Y (1) combine as XY₂."),
    q(P24, 12, "Current electricity", "MEDIUM",
      "Which of the following is the false statement about the resistance of a metallic conductor?",
      ["It depends on the temperature.", "It is directly proportional to the length.",
       "It is inversely proportional to the area of cross section.", "It depends on the potential difference across it."],
      4,
      "Resistance depends on the material, length, cross-sectional area and temperature. Changing the potential "
      "difference changes the current, not the resistance (at constant temperature).",
      ["R ∝ length", "R ∝ 1 ÷ cross-sectional area", "Resistance of a metal rises with temperature"]),
    q(P24, 14, "Hydrocarbons and their derivatives", "SIMPLE",
      "Which of the following is not a property of synthetic linear polymers?",
      ["easily biodegradable", "combustible", "resistant to acids", "electrical insulators"], 1,
      "Synthetic polymers such as polythene are not easily broken down by microorganisms; that is why plastic waste "
      "causes pollution. They burn, resist acids and do not conduct electricity."),
    q(P24, 15, "Electromagnetism and electromagnetic induction", "MEDIUM",
      "A block diagram shows some main stages of a route of transmitting electricity from an electrical power station "
      "to houses: power station → A → B → C → house. The voltage is 11 kV from the power station, 220 kV after A, "
      "22 kV after B and 220 V after C.\n\nWhat is the option that gives the transformer types indicated as A, B and C "
      "in it respectively?",
      ["step-up, step-down, step-down", "step-up, step-up, step-up", "step-down, step-up, step-up",
       "step-down, step-down, step-up"], 1,
      "A raises 11 kV to 220 kV (step-up) for transmission with less energy loss. B lowers 220 kV to 22 kV and C lowers "
      "22 kV to 220 V for homes, so both are step-down transformers.",
      ["High-voltage transmission reduces energy loss in the lines"]),
    q(P24, 16, "Biological processes in human body", "SIMPLE",
      "Which chamber of the human heart has the thickest walls?",
      ["left atrium", "right atrium", "left ventricle", "right ventricle"], 3,
      "The left ventricle pumps oxygenated blood through the aorta to the whole body, so it needs the thickest, most "
      "muscular wall."),
    q(P24, 17, "Acids, bases and salts", "MEDIUM",
      "The waste water released by a factory was passed through a packing of a solid substance X before emptying into "
      "the environment. The waste water entering the packing has a pH of 2.1, and the water released from it has a pH "
      "of 6.8.\n\nWhich of the following statement can be true about the waste water and X?",
      ["Waste water is acidic and X is sand.", "Waste water is basic and X is sand.",
       "Waste water is acidic and X is limestone.", "Waste water is basic and X is limestone."], 3,
      "pH 2.1 is strongly acidic. Limestone (calcium carbonate) neutralises the acid, raising the pH to nearly 7. "
      "Sand does not react with acids.",
      ["pH below 7: acidic; 7: neutral; above 7: basic"]),
    q(P24, 18, "Mixtures", "MEDIUM",
      "When the cap of a bottle of soda water is opened, carbon dioxide gas bubbles are liberated. More over, carbon "
      "dioxide gas bubbles are liberated faster when a bottle of soda water kept outside a refrigerator is opened than "
      "when a bottle of soda water kept inside a refrigerator is opened. According to these observations, under which "
      "of the following conditions could it be concluded that the solubility of carbon dioxide in water increases?",
      ["higher pressure and higher temperature", "higher pressure and lower temperature",
       "lower pressure and higher temperature", "lower pressure and lower temperature"], 2,
      "Opening the cap lowers the pressure and gas escapes, so higher pressure dissolves more gas. The warm bottle "
      "releases gas faster, so a lower temperature dissolves more gas.",
      ["Gas solubility rises with pressure", "Gas solubility falls as temperature rises"]),
    q(P24, 19, "Geometrical optics", "SIMPLE",
      "Which of the following statement is true about the image formed by a plane mirror?",
      ["The image is formed on the side of the object itself.", "The size of the image depends on the size of the mirror.",
       "The image is laterally inverted.", "The image distance depends on the geometrical shape of the mirror."], 3,
      "A plane mirror forms a virtual, upright image behind the mirror, the same size as the object, as far behind "
      "the mirror as the object is in front, and laterally inverted (left and right swapped)."),
    q(P24, 20, "Waves and their applications", "MEDIUM",
      "A telecommunication satellite is orbited at a height of 36 000 km from the level of the Earth. What is the time "
      r"taken in seconds for a radio wave sent by a transmitting station on the Earth to reach the satellite? (Speed of "
      r"radio waves in air is \(3.0\times10^{8}\ \mathrm{m\,s^{-1}}\).)",
      [r"\(\dfrac{36000}{3.0\times10^{8}}\)", r"\(\dfrac{3.0\times10^{8}}{36000}\)",
       r"\(\dfrac{3.0\times10^{8}}{3.6\times10^{7}}\)", r"\(\dfrac{3.6\times10^{7}}{3.0\times10^{8}}\)"], 4,
      r"Time = distance ÷ speed. The distance must be in metres: 36 000 km = \(3.6\times10^{7}\) m, so "
      r"time = \(3.6\times10^{7} \div 3.0\times10^{8}\) s. Option 1 forgets to change km to m.",
      ["Time = distance ÷ speed", "Convert km to m before dividing by a speed in m/s"]),
    q(P24, 21, "Chemical basis of life", "MEDIUM",
      "When a small amount of Benedict solution is added to a certain food extract and heated, no colour change "
      "occurred. When a little of dilute sulphuric acid is added to it and heated, the solution turned orange. The "
      "nutrient contained in this food extract would be,",
      ["glucose.", "sucrose.", "fat.", "protein."], 2,
      "Sucrose is a non-reducing sugar, so it gives no colour with Benedict solution. Heating with dilute acid "
      "hydrolyses it to glucose and fructose, which are reducing sugars and give the orange-red colour.",
      ["Benedict test detects reducing sugars", "Sucrose must first be hydrolysed with dilute acid"]),
    q(P24, 22, "The world of life", "MEDIUM",
      "Consider the following characteristics possessed by a living species.\n"
      "A - An organized nucleus is absent.\nB - can be destroyed by antibiotics\n"
      "C - can be either pathogenic or autotrophic\n\n"
      "Of these, the characteristics owned by the organisms belonging to domain bacteria are,",
      ["only A and B.", "only A and C.", "only B and C.", "all A, B and C."], 4,
      "Bacteria are prokaryotes with no organised nucleus; antibiotics kill them; some cause disease (pathogenic) and "
      "some make their own food (autotrophic), for example cyanobacteria."),
    q(P24, 23, "Changes in matter", "MEDIUM",
      "The metals P and Q react with dilute acids liberating hydrogen gas while the metal R does not liberate hydrogen "
      "with dilute acids. P cannot displace Q from a solution of a salt of Q. What is the sequential order in which the "
      "metals P, Q and R are placed in the activity series?",
      ["P, Q, R", "Q, P, R", "P, R, Q", "R, P, Q"], 2,
      "P and Q are above hydrogen and R is below it, so R is last. P cannot displace Q, so Q is more reactive than P. "
      "Order from most reactive: Q, P, R.",
      ["A more reactive metal displaces a less reactive one from its salt solution"]),
    q(P24, 24, "Hydrostatic pressure and its applications", "HARD",
      "An arrangement named A (a transparent pen clip weighted with a lump of clay) has been introduced into a well "
      "stoppered plastic bottle filled with water so that it floats immersed. When the bottle is squeezed at its middle "
      "the arrangement A moves down the bottle. The reason for this is,",
      ["increase in the pressure of water.", "increase in the weight of arrangement A.",
       "decrease in the upthrust exerted on A.", "decrease in the volume of water."], 1,
      "Squeezing increases the pressure in the water, and liquids pass pressure on equally. The extra pressure "
      "squeezes the air trapped in the pen clip, more water goes in, and A sinks. The root cause is the increase in "
      "water pressure (this is a Cartesian diver).",
      ["Pressure applied to an enclosed liquid is transmitted equally (Pascal's principle)"]),
    q(P24, 25, "Waves and their applications", "MEDIUM",
      "Given below are three statements relating to motion of waves.\n"
      "A - Transverse waves travel in a direction perpendicular to the direction of the vibration of particles in the "
      "medium.\nB - Longitudinal waves can travel in free space (vacuum).\n"
      "C - Electromagnetic waves travel at the same speed in any medium.\n\n"
      "The correct statement/s of the above is/are,",
      ["only A.", "only B.", "only A and B.", "only B and C."], 1,
      "A is correct. B is wrong: longitudinal waves such as sound need a medium. C is wrong: electromagnetic waves "
      "travel fastest in a vacuum and slower in materials such as glass or water."),
    q(P24, 26, "Acids, bases and salts", "MEDIUM",
      "Of the two solutions A and B, no colour change occurred when a few drops of phenolphthalein was added to "
      "solution A. When equal volumes of solutions of A and B of equal concentration were mixed and allowed to react, "
      "an aqueous solution of C was obtained and its pH was 7. The solutions A, B and C respectively could be,",
      ["HCl, NaOH and NaCl.", "HCl, NaCl and NaOH.", "NaOH, HCl and NaCl.", "NaOH, NaCl and HCl."], 1,
      "Phenolphthalein stays colourless in acid, so A is an acid (HCl). It reacts with the base B (NaOH) in equal "
      "amounts to give the neutral salt solution C (NaCl), pH 7.",
      ["Phenolphthalein: colourless in acid, pink in base"]),
    q(P24, 27, "Photosynthesis", "MEDIUM",
      "The carbohydrate products first formed during photosynthesis, stored temporarily in chloroplasts and "
      "translocated through the phloem respectively are,",
      ["monosaccharides, disaccharides and polysaccharides.", "monosaccharides, polysaccharides and disaccharides.",
       "disaccharides, polysaccharides and monosaccharides.", "polysaccharides, monosaccharides and disaccharides."],
      2,
      "Glucose (a monosaccharide) is formed first, stored for a while in the chloroplast as starch (a "
      "polysaccharide), and carried through the phloem as sucrose (a disaccharide)."),
    q(P24, 29, "Inheritance", "MEDIUM",
      "The number of autosomes and the sex chromosome that can be present in a human ovum respectively are,",
      ["22 and Y.", "22 and X.", "23 and Y.", "23 and X."], 2,
      "An ovum is haploid: 23 chromosomes, made of 22 autosomes and one sex chromosome, which is always X in a female.",
      ["Human body cells: 44 autosomes + XX or XY", "Gametes: 22 autosomes + one sex chromosome"]),
    q(P24, 30, "Biological processes in human body", "MEDIUM",
      "What is the option which correctly indicates how the curvature of the diaphragm and the volume of the "
      "thoracic cavity change when inspiration occurs in the respiratory process of a human?",
      ["curvature of the diaphragm increases; volume of the thoracic cavity increases",
       "curvature of the diaphragm increases; volume of the thoracic cavity decreases",
       "curvature of the diaphragm decreases; volume of the thoracic cavity increases",
       "curvature of the diaphragm decreases; volume of the thoracic cavity decreases"], 3,
      "During inspiration the diaphragm muscles contract and the dome flattens (curvature decreases), so the thoracic "
      "cavity gets larger, its pressure falls, and air flows in."),
    q(P24, 31, "Power and energy of electric appliances", "SIMPLE",
      "The power of an electric heater is 1 000 W. The amount of electrical energy spent when this heater operated for "
      "a period of 2 hours was,",
      ["0.5 kW h.", "2 kW h.", "500 kW h.", "2 000 kW h."], 2,
      "Energy = power × time = 1 kW × 2 h = 2 kW h.",
      ["Energy (kW h) = power (kW) × time (h)"]),
    q(P24, 32, "Electrochemistry", "SIMPLE",
      "Which of the following metals can be used for cathodic protection of iron?",
      ["Ag", "Cu", "Sn", "Zn"], 4,
      "For cathodic (sacrificial) protection the metal must be more reactive than iron, so that it corrodes instead "
      "of the iron. Zinc is above iron in the activity series; Ag, Cu and Sn are below it."),
    q(P24, 33, "Newton's laws of motion", "MEDIUM",
      "The force exerted vertically upwards on a rocket of mass m due to combustion of fuel is R. If g is the "
      "acceleration due to gravity, the acceleration with which the rocket moves is,",
      [r"\(\dfrac{R - mg}{m}\)", r"\(\dfrac{R}{m}\)", r"\(\dfrac{R - m}{mg}\)", r"\(\dfrac{R}{mg}\)"], 1,
      "The weight mg acts downwards, so the resultant upward force is R − mg. By F = ma, a = (R − mg) ÷ m.",
      ["Resultant force = ma"]),
    q(P24, 34, "Quantification of elements and compounds", "HARD",
      "Four gaseous hydrocarbons have these masses: A, 8.0 g of CH₄; B, 7.0 g of C₂H₄; C, 10.0 g of C₂H₆; "
      "D, 11.0 g of C₃H₈. In which of them equal number of molecules are contained? (C = 12, H = 1)",
      ["A and B", "A and C", "B and D", "C and D"], 3,
      "Moles = mass ÷ molar mass: CH₄ 8 ÷ 16 = 0.5; C₂H₄ 7 ÷ 28 = 0.25; C₂H₆ 10 ÷ 30 ≈ 0.33; C₃H₈ 11 ÷ 44 = 0.25. "
      "B and D have the same number of moles, so the same number of molecules.",
      ["Equal moles contain equal numbers of molecules"]),
    q(P24, 36, "Chemical bonds", "MEDIUM",
      "The boiling points of hydrogen chloride and water are -85 °C and 100 °C respectively. Which of the following "
      "statements explains the cause for this difference?",
      ["The intermolecular forces among hydrogen chloride molecules are weaker than the intermolecular forces among "
       "water molecules.",
       "The relative molecular mass of hydrogen chloride is greater than the relative molecular mass of water.",
       "The covalent bonds among the atoms are stronger in water than those in hydrogen chloride.",
       "Water molecules are polar whereas hydrogen chloride molecules are non-polar."], 1,
      "Boiling separates molecules from each other, so it depends on intermolecular forces, not the covalent bonds "
      "inside the molecules. Water molecules attract each other strongly (hydrogen bonds); HCl molecules attract each "
      "other more weakly. Both molecules are polar."),
    q(P24, 38, "Biosphere", "MEDIUM",
      "Of the following, which one is the false statement?",
      ["Acid rains occur due to release of chlorofluorocarbons to the atmosphere.",
       "Greenhouse effect is necessary to maintain life.", "Greenhouse effect affects global warming.",
       "Oxides of nitrogen affect depletion of the ozone layer."], 1,
      "Acid rain is caused by oxides of sulphur and nitrogen. Chlorofluorocarbons deplete the ozone layer; they do not "
      "cause acid rain."),
    q(P24, 39, "Biosphere", "SIMPLE",
      "Which of the following disaster conditions has the minimum influence of humans?",
      ["occurrence of tsunami", "occurrence of photochemical smog", "spread of invasive alien plants",
       "invasion of settlements by wild animals"], 1,
      "A tsunami is a natural disaster caused by undersea earthquakes. Photochemical smog, invasive plants and "
      "human–animal conflict are largely caused by human activities."),
    q(P24, 40, "Biosphere", "SIMPLE",
      "In a traditional tank (wewa), what is the structure that prevents the erosion of the tank bund?",
      ["bisokotuwa", "ralapanawa", "out spill", "wew thaulla"], 2,
      "The ralapanawa is a line of stones on the inner face of the bund that breaks the waves, protecting the bund "
      "from erosion. The bisokotuwa controls water released from the tank."),

    # ------------------------------------------------------------------------------------------- 2025(2026) Paper I
    q(P25, 1, "Chemical basis of life", "SIMPLE",
      "All biomolecules carbohydrates, proteins, lipids and nucleic acids contain,",
      ["carbon, hydrogen and oxygen.", "carbon, oxygen and nitrogen.", "hydrogen, oxygen and nitrogen.",
       "carbon, hydrogen and phosphorus."], 1,
      "All four groups of biomolecules contain carbon, hydrogen and oxygen. Nitrogen is in proteins and nucleic "
      "acids, and phosphorus in nucleic acids, but not in all carbohydrates and lipids."),
    q(P25, 2, "Structure and functions of the plant and animal cell", "SIMPLE",
      "An organelle that can be seen when cheek cells are observed under the optical microscope is,",
      ["Golgi complex.", "mitochondrion.", "nucleus.", "ribosome."], 3,
      "The nucleus is large enough to be seen (especially after staining) with a light microscope. Golgi complex, "
      "mitochondria and ribosomes need an electron microscope."),
    q(P25, 3, "Resultant force", "SIMPLE",
      "On a block of wood placed on a smooth horizontal table, horizontal, parallel forces act: 5 N and 7 N to the "
      "left, and 24 N to the right. What is the resultant force acting on the object?",
      ["12 N to left", "12 N to right", "22 N to left", "22 N to right"], 2,
      "Forces to the left add to 5 + 7 = 12 N. The resultant is 24 − 12 = 12 N towards the larger side, to the right."),
    q(P25, 4, "Acids, bases and salts", "SIMPLE",
      "The cause for the pain when stung by red ants is the formic acid which they apply on the sting. Application of "
      "which of the following on the sting is most effective to relieve the pain?",
      ["salt water", "water", "lime water", "vinegar"], 3,
      "A mild base neutralises the acid. Lime water (calcium hydroxide solution) is basic; vinegar is acidic and salt "
      "water and water are neutral."),
    q(P25, 5, "The world of life", "MEDIUM",
      "Of the following which statement is true?",
      ["All non- flowering plants are gymnosperms.", "Flowering plants are also called gymnosperms.",
       "Monocotyledonous and dicotyledonous plants are commonly referred to as angiosperms.",
       "There are two groups of flowering plants as seed plants and seedless plants."], 3,
      "Angiosperms (flowering plants) are divided into monocotyledons and dicotyledons. Non-flowering plants also "
      "include mosses and ferns, not only gymnosperms, and all flowering plants have seeds."),
    q(P25, 6, "Characteristics of organisms", "MEDIUM",
      "A cell, a tissue and an organ in the blood circulatory system respectively are,",
      ["neutrophils, blood and blood vessels.", "platelets, blood plasma and blood vessels.",
       "blood capillaries, arteries and heart.", "eosinophils, blood capillaries and heart."], 1,
      "A neutrophil is a white blood cell, blood is a (connective) tissue, and blood vessels are organs. Platelets are "
      "cell fragments, plasma is not a tissue on its own, and a capillary is not a single cell."),
    q(P25, 7, "Structure of matter", "MEDIUM",
      "Which of the following species contains 10 electrons, 11 protons and 12 neutrons?",
      [r"\(\ce{^{24}_{12}Mg^{2+}}\)", r"\(\ce{^{23}_{11}Na+}\)", r"\(\ce{^{23}_{11}Na}\)", r"\(\ce{^{19}_{9}F-}\)"], 2,
      "11 protons means sodium. Neutrons = 23 − 11 = 12. A Na⁺ ion has lost one electron, leaving 10 electrons.",
      ["Mass number − atomic number = number of neutrons", "A positive ion has fewer electrons than protons"]),
    q(P25, 8, "Mixtures", "MEDIUM",
      "The essential oil methyl jasmonate is the cause for the fragrance of jasmine flowers. Which following method "
      "could be used to separate methyl jasmonate industrially from jasmine flowers?",
      ["simple distillation", "steam distillation", "fractional distillation", "paper chromatography"], 2,
      "Essential oils are extracted from plant material by steam distillation: the oil evaporates with the steam at "
      "below its own boiling point, so it is not damaged by heat."),
    q(P25, 9, "Inheritance", "MEDIUM",
      "Which pair of following disorders is an example for the genetic disorders caused by gene mutation?",
      ["haemophilia and red-green colour blindness", "haemophilia and albinism",
       "red-green colour blindness and thalassemia", "albinism and thalassemia"], 4,
      "The answer key gives albinism and thalassemia: both are caused by mutations of genes on autosomes. Haemophilia "
      "and red-green colour blindness are taught as sex-linked disorders (genes on the X chromosome)."),
    q(P25, 10, "Continuity of life", "SIMPLE",
      "Which of the following option correctly indicates a type of an underground stem and an example relevant to it?",
      ["rhizome - manioc", "corm - ginger", "bulb - sweet potato", "stem tuber - potato"], 4,
      "Potato is a stem tuber. Ginger is a rhizome, manioc and sweet potato are root tubers, and onion is a bulb."),
    q(P25, 12, "Power and energy of electric appliances", "SIMPLE",
      "A transformer is supplied with a power of 690 W from a supply of 230 V. How much is the current supplied to the "
      "coil?",
      ["1 A", "2 A", "3 A", "4 A"], 3,
      "P = VI, so I = P ÷ V = 690 ÷ 230 = 3 A.",
      ["Power = voltage × current"]),
    q(P25, 13, "Waves and their applications", "SIMPLE",
      "Which option correctly indicates the nature of radio waves and waves created on a water surface?",
      ["radio waves: transverse - mechanical; waves on a water surface: transverse - electromagnetic",
       "radio waves: transverse - electromagnetic; waves on a water surface: transverse - mechanical",
       "radio waves: longitudinal - mechanical; waves on a water surface: longitudinal - mechanical",
       "radio waves: longitudinal - electromagnetic; waves on a water surface: longitudinal - electromagnetic"], 2,
      "Radio waves are electromagnetic waves, which are transverse. Water-surface waves need a medium (mechanical) "
      "and are treated as transverse."),
    q(P25, 15, "Structure of matter", "MEDIUM",
      "X, Y and Z are three successive elements in the periodic table and X is a noble gas. As which of the following "
      "species is Z most likely to exist in its compounds?",
      [r"\(\ce{Z+}\)", r"\(\ce{Z^2+}\)", r"\(\ce{Z}\)", r"\(\ce{Z-}\)"], 2,
      "After a noble gas X comes an alkali metal Y (group 1), then an alkaline earth metal Z (group 2). Z loses its two "
      "outer electrons and forms Z²⁺ ions."),
    q(P25, 16, "Electromagnetism and electromagnetic induction", "MEDIUM",
      "Four rules relating to electromagnetism and electromagnetic induction are given below.\n"
      "A - Maxwell's corkscrew rule\nB - Right hand rule\nC - Fleming's left hand rule\n"
      "D - Fleming's right hand rule\n\n"
      "Of these, the two rules that can be used to find the direction of the magnetic field around a conductor "
      "carrying an electric current are,",
      ["A and B.", "A and C.", "B and C.", "B and D."], 1,
      "Maxwell's corkscrew rule and the right-hand (grip) rule give the direction of the magnetic field around a "
      "current. Fleming's left-hand rule gives the force on a conductor (motors); the right-hand rule gives the "
      "induced current (generators)."),
    q(P25, 17, "Newton's laws of motion", "HARD",
      "Consider the following statements.\n"
      "A - An unbalanced force does not act on an object at rest freely suspended by a string.\n"
      "B - An unbalanced force acts on an object moving with a uniform velocity.\n"
      "C - The force exerted on the table by an object kept at rest on a horizontal table is equal to the force exerted "
      "by the table on the object in the opposite direction.\n\n"
      "Of the above, the true statement/statements is/are,",
      ["only A.", "only A and B.", "only A and C.", "only B and C."], 3,
      "A: at rest, the tension balances the weight, so there is no unbalanced force (true). B: uniform velocity means "
      "no acceleration, so no unbalanced force (false). C: action and reaction are equal and opposite (true)."),
    q(P25, 19, "Photosynthesis", "SIMPLE",
      "Of the following factors essential for photosynthesis, which factor cannot be tested in the school laboratory?",
      ["water", "chlorophyll", "light", "carbon dioxide"], 1,
      "Light, chlorophyll (variegated leaves) and carbon dioxide can each be removed in simple experiments. Water "
      "cannot be removed without killing the plant, because it is needed for all its life processes."),
    q(P25, 20, "Hydrocarbons and their derivatives", "MEDIUM",
      r"The hydrocarbon with molecular formula \(\ce{C5H12}\),",
      ["is an alkene.", "is a component of biogas.", "can be polymerised.",
       "forms 6 moles of water on complete combustion of one mole."], 4,
      r"\(\ce{C5H12}\) is an alkane (pentane), so it cannot be polymerised. Biogas is mainly methane. On combustion, "
      r"its 12 hydrogen atoms form 6 molecules of water: \(\ce{C5H12 + 8O2 -> 5CO2 + 6H2O}\)."),
    q(P25, 21, "Electronics", "MEDIUM",
      "The main reason for the easy flow of electrons across the p-n junction of a forward biased diode is,",
      ["high energy in the electrons.", "very low value of the potential barrier.",
       "that the depletion region is very narrow.", "that the doping level is very low."], 3,
      "In forward bias, the applied voltage opposes the junction's potential barrier, so the depletion region becomes "
      "very narrow and charge carriers cross it easily."),
    q(P25, 23, "Rate of reactions", "MEDIUM",
      "u, v and w are three metals lying above hydrogen in the activity series. The method proposed by a group of "
      "students to compare their activity was taking a piece from each metal, reacting them with hydrochloric acid "
      "separately and comparing the volumes of hydrogen gas liberated within a fixed short period. Which of the "
      "following factors is not essential to be kept constant during the experiment?",
      ["surface area of the metals", "mass of the metals", "temperature of the reaction mixture",
       "acid concentration of the reaction mixture"], 2,
      "Over a short period, the rate depends on the surface area exposed, the temperature and the acid "
      "concentration, so these must be the same. The total mass of each piece doesn't change the early rate when "
      "the metal is in excess."),
    q(P25, 24, "Biological processes in human body", "SIMPLE",
      "During inspiration,",
      ["intercostal muscles and the muscles in the diaphragm relax.",
       "intercostal muscles relax while muscles in the diaphragm contract.",
       "intercostal muscles contract while muscles in the diaphragm relax.",
       "intercostal muscles and muscles in the diaphragm contract."], 4,
      "In inspiration the external intercostal muscles contract, lifting the ribs, and the diaphragm muscles contract, "
      "flattening it. Both make the chest cavity larger."),
    q(P25, 25, "Continuity of life", "MEDIUM",
      "Consider the following statements about amoeba.\nA - Reproduction occurs by binary fission.\n"
      "B - Binary fission occurs only by mitotic division.\n\nOf the above statements,",
      ["only statement A is true.", "only statement B is true.", "both statements A and B are true.",
       "both statements A and B are false."], 3,
      "Amoeba reproduces asexually by binary fission, in which the nucleus divides by mitosis, so both offspring are "
      "identical to the parent."),
    q(P25, 27, "Changes in matter", "MEDIUM",
      "Two metals Q and R react as indicated by the following equations.\n"
      r"\(\ce{Q(s) + H2SO4(aq) -> QSO4(aq) + H2(g)}\)" "\n"
      r"\(\ce{R(s) + H2SO4(aq) -> RSO4(aq) + H2(g)}\)" "\n"
      r"\(\ce{R(s) + QSO4(aq) ->}\) no reaction" "\n\n"
      "Which of the following can be Q and R respectively?",
      ["Cu and Zn", "Zn and Mg", "Mg and Cu", "Mg and Zn"], 4,
      "Both react with dilute acid, so both are above hydrogen (Cu is ruled out). R cannot displace Q, so Q is more "
      "reactive than R: Q = Mg and R = Zn."),
    q(P25, 28, "Changes in matter", "MEDIUM",
      "A gas jar filled with a gas X was inverted and placed vertically in a beaker of water. Then, it was seen that the "
      "water level inside the gas jar rises rapidly. Gas X can be,",
      ["Ne.", r"\(\ce{N2}\).", r"\(\ce{CO2}\).", r"\(\ce{NH3}\)."], 4,
      "The water rises quickly because the gas dissolves quickly. Ammonia is extremely soluble in water; neon and "
      "nitrogen barely dissolve, and carbon dioxide dissolves only slowly and slightly."),
    q(P25, 29, "Heat changes associated with chemical reactions", "MEDIUM",
      "In an energy level diagram, the products are at a higher energy level than the reactants. Which of the "
      "following is most likely to be the change represented by this energy level diagram?",
      ["dissolving of glucose in water", "combustion of the hydrocarbon, methane",
       "reaction between zinc and hydrochloric acid", "neutralization between sodium hydroxide and hydrochloric acid"],
      1,
      "Products higher than reactants means heat is absorbed: an endothermic change. Dissolving glucose absorbs heat; "
      "combustion, zinc with acid and neutralisation all release heat (exothermic)."),
    q(P25, 30, "Biological processes in human body", "MEDIUM",
      "The heart beat of a person whose head was injured by an accident became irregular and respiration occurred "
      "without a control. He happened to vomit also. Thus, it can be inferred that in his brain,",
      ["cerebellum is damaged.", "medulla oblongata (brain stem) is damaged.", "left cerebral hemisphere is damaged.",
       "right cerebral hemisphere is damaged."], 2,
      "The medulla oblongata controls involuntary actions such as heartbeat, breathing and vomiting. The cerebellum "
      "controls balance and coordination of movement."),
    q(P25, 31, "Friction", "SIMPLE",
      "The magnetic train operated in Japan is lifted a bit up above the rails after it acquires an adequate speed and "
      "made to run forward. What is the main advantage obtained by this modern technological strategy?",
      ["minimizing train maintenance activities", "minimizing the wear of the rails",
       "minimizing air resistance acting on the train", "running the train under minimal friction"], 4,
      "Lifting the train off the rails removes the contact between wheels and rails, so the main gain is very low "
      "friction, allowing very high speeds."),
    q(P25, 32, "Quantification of elements and compounds", "HARD",
      "Two reactions used to prepare the gases H₂ and CO₂ in the laboratory are given below.\n"
      r"\(\ce{Zn + 2HCl -> ZnCl2 + H2}\)" "\n" r"\(\ce{CaCO3 + 2HCl -> CaCl2 + H2O + CO2}\)" "\n\n"
      r"What is the mass of \(\ce{CaCO3}\) that should be reacted to obtain an amount of moles of \(\ce{CO2}\) gas "
      r"which is equal to the maximum amount of moles of \(\ce{H2}\) gas obtainable from 32 g of zinc (Zn)? "
      "(H = 1; C = 12; O = 16; Ca = 40; Zn = 64)",
      ["22 g", "44 g", "50 g", "100 g"], 3,
      r"32 g Zn ÷ 64 = 0.5 mol Zn, giving 0.5 mol \(\ce{H2}\). 0.5 mol \(\ce{CO2}\) needs 0.5 mol \(\ce{CaCO3}\) "
      r"(molar mass 100 g/mol), which is 50 g.",
      ["Moles = mass ÷ molar mass", "Use the mole ratio in the balanced equation"]),
    q(P25, 33, "Electrochemistry", "MEDIUM",
      "In the form of a thin layer, metal X is applied on the iron sheet A and metal Y is applied on the iron sheet B. "
      "A small scratch was made at the centre of both sheets. Later, rusting has spread rapidly on A, but B has not "
      "been subjected to a considerable damage. The metals X and Y respectively can be,",
      ["Sn and Zn.", "Zn and Sn.", "Cu and Ag.", "Ag and Cu."], 1,
      "Once scratched, a less reactive coating (tin) makes iron rust faster, because iron becomes the metal that "
      "corrodes. A more reactive coating (zinc, galvanising) corrodes instead of the iron, protecting it."),
    q(P25, 34, "Heat", "HARD",
      "600 g of water at temperature 80°C were added to a vessel containing 200 g of water at 0°C. What is the final "
      "temperature of the mixture? (specific heat capacity of water = 4200 J kg⁻¹ °C⁻¹; assume that the vessel does "
      "not absorb heat.)",
      ["53°C", "60°C", "66°C", "70°C"], 2,
      "Heat lost = heat gained: 0.6 × 4200 × (80 − θ) = 0.2 × 4200 × (θ − 0). So 3(80 − θ) = θ, 240 = 4θ, θ = 60 °C.",
      ["Heat = mass × specific heat capacity × temperature change", "Heat lost by hot = heat gained by cold"]),
    q(P25, 35, "Hydrostatic pressure and its applications", "MEDIUM",
      "The areas of cross section of the pistons of a hydraulic jack are x m² and 4x m². How much is the force exerted "
      "on the larger piston when a force of 50 N is applied on the smaller piston?",
      ["50 N", "100 N", "150 N", "200 N"], 4,
      "The pressure is the same on both pistons: 50 ÷ x = F ÷ 4x, so F = 4 × 50 = 200 N.",
      ["Pressure is transmitted equally through the liquid"]),
    q(P25, 37, "Biosphere", "SIMPLE",
      "Which of the following is a disease which cannot be either controlled or prevented by changing the life style?",
      ["diabetes", "gastritis", "chronic kidney disease", "haemophilia"], 4,
      "Haemophilia is an inherited (genetic) disorder, so lifestyle cannot prevent it. Diabetes, gastritis and chronic "
      "kidney disease are linked to diet, habits and environment."),
    q(P25, 38, "Biosphere", "MEDIUM",
      "In which phase of the growth curve of population does the number of organisms in the population reach its "
      "carrying capacity?",
      ["first phase", "second phase", "third phase", "fourth phase"], 4,
      "The answer key gives the fourth phase: after the slow start, the rapid growth and the slowing down, the "
      "population levels off at the carrying capacity of the environment."),
    q(P25, 39, "Biosphere", "MEDIUM",
      "Which of the following statements is true about the ecological pyramids?",
      ["Number pyramids are always upright.", "In a biomass pyramid, omnivores can exist only as secondary consumers.",
       "Heavy metals accumulate most in the highest level of a biomass pyramid.",
       "Biomagnification is the incorporation of a higher amount of energy in the higher trophic levels of an energy "
       "pyramid."], 3,
      "Heavy metals are not broken down or excreted, so they concentrate up the food chain (biomagnification) and "
      "are highest in the top consumers. Number pyramids can be inverted (for example a tree with many insects)."),
    q(P25, 40, "Biosphere", "HARD",
      "As a student says, nitrogenous nutrients in a soil decrease after a flood due to following reasons.\n"
      "A - occurrence of denitrification\nB - percolation deeper into soil (leaching)\n"
      "C - getting washed away from the surface soil\nD - decomposition of organic matter\n\nOf these only,",
      ["A, B and C are true.", "A, B and D are true.", "A, C and D are true.", "B, C and D are true."], 1,
      "Waterlogged soil lacks oxygen, which favours denitrifying bacteria (A); water carries nitrates down (B) and "
      "washes away the surface soil (C). Decomposing organic matter adds nitrogen nutrients rather than removing them."),
]


def check():
    seen = set()
    for x in QUESTIONS:
        key = (x["paper"], x["number"])
        assert key not in seen, key
        seen.add(key)
        assert x["topic"] in SUBJECT_OF, x["topic"]
        assert len(x["options"]) == 4 and 1 <= x["answer"] <= 4, key
        assert x["difficulty"] in SECONDS, key
        for text in [x["question"], x["explanation"], *x["options"]]:
            assert text.count(r"\(") == text.count(r"\)"), (key, text)


def rows():
    for x in sorted(QUESTIONS, key=lambda x: (x["paper"], x["number"])):
        exam = "2024(2025)" if x["paper"] == P24 else "2025(2026)"
        yield {
            "subject": SUBJECT_OF[x["topic"]], "module": MODULE, "topic": x["topic"], "type": "SINGLE",
            "question": x["question"],
            "option_a": x["options"][0], "option_b": x["options"][1], "option_c": x["options"][2],
            "option_d": x["options"][3], "option_e": "", "correct": "ABCD"[x["answer"] - 1],
            "explanation": x["explanation"] + f"\n\nSource: G.C.E. O/L {exam} Science Paper I, question {x['number']}.",
            "key_points": "|".join(x["key_points"]), "definitions": "", "difficulty": x["difficulty"],
            "seconds": str(SECONDS[x["difficulty"]]),
            "tags": f"past paper|ol {x['paper']}|paper {x['paper']} q{x['number']}", "marks": "1",
        }


def main():
    check()
    data = list(rows())
    csv_path = os.path.join(HERE, "ol-science-past-papers.csv")
    with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(data)
    try:
        from openpyxl import Workbook
        wb = Workbook()
        ws = wb.active
        ws.title = "Questions"
        ws.append(COLUMNS)
        for r in data:
            ws.append([r[c] for c in COLUMNS])
        wb.save(os.path.join(HERE, "ol-science-past-papers.xlsx"))
    except ImportError:
        print("openpyxl not installed: only the CSV was written")
    by_subject = {}
    for r in data:
        by_subject.setdefault(r["subject"], set()).add(r["topic"])
    print(f"{len(data)} questions written to {csv_path}")
    for s, ts in by_subject.items():
        print(f"  {s}: {len(ts)} topics")


if __name__ == "__main__":
    main()
