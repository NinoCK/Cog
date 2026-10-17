"""Builds the sentence list the cloned voice reads aloud to make the training set.

Three parts: the assistant's own replies (filled with varied times, places, names and numbers,
written out in words so the cloner and the trained voice pronounce them the same way), the
phonetically balanced CMU ARCTIC prompts, and encyclopedia-style sentences like the Wikipedia
answers. Writes work/corpus.txt, one sentence per line.

    python make_corpus.py
"""
import random
import re
from pathlib import Path

HERE = Path(__file__).parent
WORK = HERE / "work"
rng = random.Random(7)

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def words(n: int) -> str:
    if n < 0:
        return "minus " + words(-n)
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else "-" + ONES[n % 10])
    if n < 1000:
        rest = n % 100
        return ONES[n // 100] + " hundred" + ("" if rest == 0 else " and " + words(rest))
    if n < 1_000_000:
        rest = n % 1000
        return words(n // 1000) + " thousand" + ("" if rest == 0 else (" and " if rest < 100 else " ") + words(rest))
    return words(n // 1_000_000) + " million"


def clock() -> str:
    h, m = rng.randint(1, 12), rng.choice([0, 0, 5, 10, 15, 20, 30, 30, 45, 50, rng.randint(1, 59)])
    ampm = rng.choice(["AM", "PM"])
    if m == 0:
        return f"{words(h)} {ampm}"
    mm = ("oh " + ONES[m]) if m < 10 else words(m)
    return f"{words(h)} {mm} {ampm}"


def duration() -> str:
    kind = rng.random()
    if kind < 0.4:
        n = rng.randint(1, 59)
        return f"{words(n)} minute" + ("s" if n > 1 else "")
    if kind < 0.6:
        n = rng.randint(5, 59)
        return f"{words(n)} seconds"
    h, m = rng.randint(1, 11), rng.randint(1, 59)
    return f"{words(h)} hour{'s' if h > 1 else ''} and {words(m)} minute{'s' if m > 1 else ''}"


CITIES = ["London", "Athens", "Thessaloniki", "Paris", "Berlin", "Tokyo", "New York", "Sydney", "Madrid",
          "Rome", "Lisbon", "Dublin", "Toronto", "Chicago", "Cairo", "Mumbai", "Seoul", "Moscow", "Oslo",
          "Vienna", "Prague", "Amsterdam", "Istanbul", "Los Angeles", "San Francisco", "Mexico City",
          "Buenos Aires", "Cape Town", "Singapore", "Dubai", "Heraklion", "Patras", "Edinburgh", "Munich"]
PEOPLE = ["Mom", "Dad", "Nikos", "Maria", "George", "Eleni", "John", "Sarah", "Alex", "Kostas", "Anna",
          "Dimitris", "Sophia", "Michael", "Katerina", "Chris", "Emma", "Yannis", "the office", "Grandma"]
APPS = ["Spotify", "YouTube", "Gmail", "Chrome", "Maps", "WhatsApp", "Instagram", "the calculator",
        "the calendar", "Settings", "the camera", "Messages", "Viber", "Netflix", "the clock", "Files"]
MUSIC = ["Iron Maiden", "Metallica", "Queen", "Pink Floyd", "Daft Punk", "The Beatles", "Coldplay",
         "Bohemian Rhapsody", "my workout playlist", "Hans Zimmer", "Mozart", "Radiohead", "Muse",
         "Dire Straits", "Led Zeppelin", "Black Sabbath", "Rammstein", "Nirvana", "Adele", "Vangelis"]
LABELS = ["morning", "work", "gym", "pasta", "laundry", "medicine", "meeting", "nap", "pizza", "tea"]
PANELS = ["Wi-Fi", "Bluetooth", "mobile data", "airplane mode", "location", "hotspot", "NFC"]
DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
          "October", "November", "December"]
CONDITIONS = ["clear", "mostly clear", "partly cloudy", "overcast", "foggy", "drizzle", "freezing rain",
              "rain", "heavy rain", "snow", "heavy snow", "thunderstorms"]
UNITS = [("kilometers", "miles"), ("miles", "kilometers"), ("kilograms", "pounds"), ("pounds", "kilograms"),
         ("meters", "feet"), ("feet", "meters"), ("liters", "gallons"), ("inches", "centimeters"),
         ("degrees Celsius", "degrees Fahrenheit"), ("ounces", "grams")]
MESSAGES = ["I am on my way", "running ten minutes late", "call me when you can", "see you tonight",
            "happy birthday", "do not forget the milk", "the meeting is moved to Thursday", "love you"]
SEARCHES = ["the meaning of life", "quantum entanglement", "how to fix a squeaky door", "the best pizza nearby",
            "the population of Mars", "why the sky is blue", "galactic gadgets", "robot maintenance"]


def date() -> str:
    return f"{rng.choice(DAYS)}, {rng.choice(MONTHS)} {words(rng.randint(1, 28))}"


def decimal() -> str:
    whole, frac = rng.randint(0, 999), rng.randint(1, 9)
    return f"{words(whole)} point {ONES[frac]}" if rng.random() < 0.5 else words(rng.randint(1, 5000))


TEMPLATES = [
    lambda: f"Affirmative. Your alarm is set for {clock()}, which is {duration()} from now.",
    lambda: f"Affirmative. Your {rng.choice(LABELS)} alarm is set for {clock()}, which is {duration()} from now.",
    lambda: "Here are your alarms.",
    lambda: f"Timer engaged: {duration()}, counting down now.",
    lambda: f"Your {rng.choice(LABELS)} timer is set for {duration()}. Counting down now.",
    lambda: "Here are your timers.",
    lambda: "Illumination activated.",
    lambda: "Illumination deactivated.",
    lambda: "Increasing the volume.",
    lambda: "Lowering the volume.",
    lambda: "Silence engaged.",
    lambda: "Sound restored.",
    lambda: f"Media volume set to {words(rng.randrange(0, 101, 5))} percent.",
    lambda: "Resuming playback.",
    lambda: "Playback paused.",
    lambda: "Skipping ahead.",
    lambda: "Returning to the previous track.",
    lambda: f"Splendid choice. Playing {rng.choice(MUSIC)} on Spotify.",
    lambda: f"Splendid choice. Playing {rng.choice(MUSIC)}.",
    lambda: f"This is {rng.choice(MUSIC)}.",
    lambda: f"This is {rng.choice(MUSIC)}, by {rng.choice(MUSIC)}.",
    lambda: "Nothing is playing at the moment.",
    lambda: "Added to your liked songs. An excellent choice.",
    lambda: rng.choice(["Shuffle engaged.", "Shuffle disengaged.", "Repeat engaged.", "Repeat disengaged."]),
    lambda: f"Plotting a course to {rng.choice(CITIES)}.",
    lambda: "Opening the camera. Do smile.",
    lambda: "Opening the camera to record.",
    lambda: f"Here are your {rng.choice(PANELS)} settings.",
    lambda: f"Here is the {rng.choice(PANELS)} switch. I am afraid Android insists you flip it yourself.",
    lambda: f"Calling {rng.choice(PEOPLE)}.",
    lambda: f"Composing a message to {rng.choice(PEOPLE)}.",
    lambda: f"A message to {rng.choice(PEOPLE)} that reads: {rng.choice(MESSAGES)}. Simply tap send.",
    lambda: f"Opening {rng.choice(APPS)}.",
    lambda: f"By my calculations, that is {decimal()}.",
    lambda: f"I am afraid that is beyond my programming. Here is what I found for {rng.choice(SEARCHES)}.",
    lambda: (lambda u: f"{words(rng.randint(1, 500))} {u[0]} is {decimal()} {u[1]}.")(rng.choice(UNITS)),
    lambda: f"It is {clock()} on {date()}.",
    lambda: f"It is {clock()} in {rng.choice(CITIES)}.",
    lambda: "That is the same time as here.",
    lambda: f"That is {words(rng.randint(1, 11))} hours ahead of you.",
    lambda: f"That is {words(rng.randint(1, 11))} hours behind you.",
    lambda: f"Right now in {rng.choice(CITIES)} it is {words(rng.randint(-5, 40))} degrees and {rng.choice(CONDITIONS)}.",
    lambda: f"Right now it is {words(rng.randint(-5, 40))} degrees and {rng.choice(CONDITIONS)}.",
    lambda: f"It feels like {words(rng.randint(-8, 42))}.",
    lambda: f"Today's high is {words(rng.randint(10, 40))} and the low is {words(rng.randint(-5, 20))}.",
    lambda: f"There is a {words(rng.randrange(40, 101, 5))} percent chance of rain. I recommend an umbrella.",
    lambda: f"There is a {words(rng.randrange(5, 40, 5))} percent chance of rain.",
    lambda: "No rain is expected.",
    lambda: f"Tomorrow: {rng.choice(CONDITIONS)}, with a high of {words(rng.randint(10, 38))} and a low of {words(rng.randint(-3, 20))} degrees.",
    lambda: f"The warmest day this week is {rng.choice(DAYS)}, at {words(rng.randint(15, 40))} degrees.",
    lambda: "It looks dry all week.",
    lambda: f"Rain is likely on {rng.choice(DAYS)} and {rng.choice(DAYS)}.",
    lambda: f"I could not find a place called {rng.choice(['Zorgon', 'Metropolis', 'Kerwan', 'Veldin', 'Aridia'])}.",
    lambda: "I am unable to reach the weather service at the moment.",
    lambda: "To report the local weather I require your approximate location. Tap Allow, or ask about a city.",
    lambda: f"My search of Wikipedia found nothing about {rng.choice(SEARCHES)}.",
    lambda: "I am unable to reach Wikipedia at the moment.",
]

# Short in-character lines: greetings, acknowledgements and errors the assistant may say.
PERSONA = """Yes? How may I assist you?
I am listening.
Certainly.
Right away.
Of course.
Affirmative.
Negative.
I am sorry, I did not quite catch that.
Could you repeat that, please?
I do not have permission to do that.
Processing your request.
One moment, please.
All systems operational.
Task complete.
Goodbye for now.
Good morning. I trust you slept well.
Good evening.
That is most peculiar.
How fascinating.
I am afraid something has gone wrong.
Please grant me access in the settings.
Hello. My name is Cog.
I am always glad to help.
Excellent. Shall I do anything else?
That is not something I can do yet.
I could not find a contact by that name.
I could not find an app by that name.
Your battery is running low.
Connection lost. Please try again.
Spotify does not appear to be installed.""".splitlines()

# Encyclopedia-style sentences, so long factual answers sound natural.
ENCYCLOPEDIA = """The Eiffel Tower is a wrought iron lattice tower in Paris, completed in eighteen eighty-nine.
Mount Everest is the highest mountain above sea level, located in the Himalayas.
The Parthenon is a former temple on the Athenian Acropolis, dedicated to the goddess Athena.
Photosynthesis is the process by which green plants convert light energy into chemical energy.
The Pacific Ocean is the largest and deepest of the world's five oceanic divisions.
Albert Einstein was a German-born theoretical physicist who developed the theory of relativity.
The human heart pumps blood through the circulatory system by rhythmic contraction.
Jupiter is the fifth planet from the Sun and the largest in the Solar System.
The Great Wall of China is a series of fortifications built across the northern borders of ancient China.
A volcano is a rupture in the crust of a planet that allows hot lava and gases to escape.
William Shakespeare was an English playwright, poet and actor, widely regarded as the greatest writer in the English language.
The Amazon rainforest covers much of the Amazon basin of South America.
Iron Maiden are an English heavy metal band formed in Leyton, East London, in nineteen seventy-five.
The Moon is Earth's only natural satellite, orbiting at an average distance of about three hundred and eighty-four thousand kilometers.
Leonardo da Vinci was an Italian polymath of the High Renaissance, known for the Mona Lisa.
A black hole is a region of spacetime where gravity is so strong that nothing can escape it.
The Olympic Games originated in ancient Greece, at Olympia, in the eighth century before Christ.
Python is a high-level, general-purpose programming language emphasizing code readability.
The Nile is a major north-flowing river in northeastern Africa.
The Acropolis of Athens is an ancient citadel above the city, crowned by the Parthenon.
Antarctica is Earth's southernmost and least populated continent.
The Industrial Revolution was the transition to new manufacturing processes in Europe and the United States.
Coffee is a beverage brewed from roasted coffee beans, the seeds of berries from certain Coffea species.
Marie Curie was a Polish and naturalised French physicist and chemist who conducted pioneering research on radioactivity.
The Roman Empire was the post-Republican state of ancient Rome, ruling much of Europe and the Mediterranean.
An electric guitar is a guitar that requires external amplification to be heard at typical performance volumes.
Saturn is known for its prominent ring system, composed mainly of ice particles.
DNA is a polymer composed of two chains that coil around each other to form a double helix.
The Acropolis of Athens is an ancient citadel located on a rocky outcrop above the city.
Tokyo is the capital and most populous city of Japan.
A robot is a machine capable of carrying out a complex series of actions automatically.
The Renaissance was a period of European history marking the transition from the Middle Ages to modernity.
Penguins are a group of aquatic flightless birds living almost exclusively in the Southern Hemisphere.
The speed of light in vacuum is exactly two hundred and ninety-nine million, seven hundred and ninety-two thousand, four hundred and fifty-eight meters per second.
Chess is a board game for two players, played on a checkered board with sixty-four squares.
Aristotle was an Ancient Greek philosopher and polymath, a student of Plato.
The internet is a global system of interconnected computer networks.
Wolfgang Amadeus Mozart was a prolific and influential composer of the Classical period.
Crete is the largest and most populous of the Greek islands.
Hydrogen is the lightest element and the most abundant chemical substance in the universe.
The Titanic was a British ocean liner that sank in the North Atlantic Ocean in nineteen twelve.
Basketball is a team sport in which two teams of five players try to score by shooting a ball through a hoop.
Vincent van Gogh was a Dutch Post-Impressionist painter, among the most famous figures in Western art.
The Sahara is a desert spanning North Africa, the largest hot desert in the world.
An earthquake is the shaking of the Earth's surface resulting from a sudden release of energy.
Alexander the Great was a king of the ancient Greek kingdom of Macedon.
Mars is the fourth planet from the Sun, often called the Red Planet.
The violin is a wooden string instrument, the smallest and highest-pitched in the violin family.
Bees are winged insects closely related to wasps and ants, known for their role in pollination.
The Colosseum is an elliptical amphitheatre in the centre of the city of Rome.
Artificial satellites orbit the Earth for communication, navigation and weather observation.
Thomas Edison was an American inventor and businessman who developed many devices in electric power generation.
The Mediterranean Sea is connected to the Atlantic Ocean and surrounded by the Mediterranean Basin.
A smartphone is a mobile device that combines the functions of a computer and a telephone.
Gravity is a fundamental interaction which causes mutual attraction between all things that have mass.
The Beatles were an English rock band formed in Liverpool in nineteen sixty.
Olive oil is a liquid fat obtained by pressing whole olives, a traditional crop of the Mediterranean.
Isaac Newton was an English mathematician and physicist who formulated the laws of motion.
The Amazon River in South America is the largest river by discharge volume of water in the world.
Climate change refers to long-term shifts in temperatures and weather patterns.
Homer is the presumed author of the Iliad and the Odyssey, two epic poems of ancient Greek literature.
A galaxy is a system of stars, stellar remnants, gas, dust and dark matter bound together by gravity.
Football, also called soccer, is a team sport played between two teams of eleven players.
The piano is a keyboard instrument that produces sound when its keys are depressed.
The Milky Way is the galaxy that includes the Solar System.
Socrates was a Greek philosopher from Athens who is credited as the founder of Western philosophy.
Venice is a city in northeastern Italy, built on a group of one hundred and eighteen small islands.
Electricity is the set of physical phenomena associated with the presence and motion of electric charge.
The Statue of Liberty is a colossal neoclassical sculpture on Liberty Island in New York Harbor.
Honey is a sweet and viscous substance made by honey bees.
The printing press was invented by Johannes Gutenberg around fourteen forty.
A rainbow is an optical phenomenon caused by refraction, reflection and dispersion of light in water droplets.
Tigers are the largest living cat species, recognisable by their dark vertical stripes.
The Andes are the longest continental mountain range in the world.
Linux is a family of open-source operating systems based on the Linux kernel.
The Wright brothers are credited with inventing and flying the world's first successful motor-operated airplane.
Pi is a mathematical constant, the ratio of a circle's circumference to its diameter, approximately three point one four.
The Odyssey follows the Greek hero Odysseus on his ten-year journey home after the Trojan War.
Lightning is a natural electrostatic discharge during a thunderstorm.""".splitlines()


def arctic() -> list[str]:
    lines = []
    for raw in (WORK / "cmuarctic.data").read_text(encoding="utf-8").splitlines():
        m = re.search(r'"(.+)"', raw)
        if m:
            text = m.group(1).strip()
            lines.append(text if text[-1] in ".?!" else text + ".")
    return lines


def main() -> None:
    sentences = []
    for template in TEMPLATES:
        seen = set()
        for _ in range(14):
            text = template()
            if text not in seen:
                seen.add(text)
                sentences.append(text)
    sentences += PERSONA * 2  # short lines twice: different takes add variety
    sentences += ENCYCLOPEDIA
    sentences += arctic()
    rng.shuffle(sentences)
    (WORK / "corpus.txt").write_text("\n".join(sentences) + "\n", encoding="utf-8")
    print(len(sentences), "sentences")


if __name__ == "__main__":
    main()
