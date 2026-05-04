requirements:
meteor client 1.21.4 idk release
minecraft 1.21.4
baritone
BARITONE PREFIX MUST BE "#" or coords WILL be leaked in chat without being displayed too you
turn off minecraft focus by using f3+p
Modules:
AutoChestDeposit:

automaticalyy moves items from inventory to any container, allows u to set filter item or filter name if filter name put filter items to none

on 6b6t if delay is 0 u will get kicked and will have to wait a bit before it lets you rejoin
AutochestSteal:

same as Deposit just takes items

for filling/emptying of entire enderchest:
AutoStashDeposit:

allows user to set 3 positions, uses baritone to take from pos 1 and empty into pos 2 if pos 2 is full it will use pos3 as overflow

turn modual on click button in gui for each pos exit gui left click chest/echest
AutoStashSteal:

same as AutoStashDeposit just other way

PearlLoaderModule:

extreamly simple just clicks when it detects that you where sent message(trigger):

designed for an alt account, turn modual on configure your trigger word and point at the hinge of the trapdoor then leave it and use your main
i recomend making your trigger unique as to not be accidentaly triggered by a dm spam bot
i will add more features to this such as baritone pathing to the stasis
AutoStashCycle:

main reason i wanted an addon

allows user to assign chests/enderchest to steal/deposit runs tpa to a alt account i recommend using asteoide addon's auto macro as you can set it detect a trigger as "{player} wants to tp to you" and it then runs tpy, it will then wait and once tpd it will continue with the workflow by locating enderchest you assigned stealing and depositing before finnaly running /kill and repeating the cycle
bed must be set at stash you found
chest assign: (for both sets of positions)

1: MAIN CHEST, this is main chest to steal/deposit u have to choose this twice in both your found stash and your stash
2: ENDER CHEST , has to be assigned twice
3: OVERFLOW CHEST, optional but highly recommended, used if chest 1 is full will steal/deposit in this

a hopper setup is recommended, i will add support for multi chest soon,
settings:
cycles: number of times that it will repeat one steal-->tpa-->deposit-->kill cycle low numbers recommended as i havnt added cooldown support yet
Tpa command: command to configure the tpa eg.".delay 1 /tpa Rocky_proxy" must be .delay
kill commadn: command to kill player eg.".delay 1 /kill" must be .delay
start: starts cycle
reset: resets all positions
blockfilters: which items to take/deposit
namefilters: which items with custom names to take, block filters must be 0 selected while using this
stealslots: positions set at stash you found
deposit slots: positons set at your stash, recommend setting these first then doing /kill
gui open delay: time to wait after opening container before stealing/depositing, 6 recommended
item delay: delay between each item move, 3-4 recommended or you will get kicked
tpa delay: time(seconds) to wait after sending tpa, recommended 17-20
kill delay: time(seconds) to wait after running /kill, any greater then 7 should work

note: 6b uses server swithcing thing that has 2 respawn gui's so a externall mod is recommeded if u have a end stash you want to move loot too https://modrinth.com/mod/auto-respawn
