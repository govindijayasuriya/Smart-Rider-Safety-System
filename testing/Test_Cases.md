| ID  | Test          | Input                         | Expected Output        |
| --- | ------------- | ----------------------------- | ---------------------- |
| T01 | Normal        | No object/no hazard           | SAFE                   |
| T02 | Left hazard   | Object left                   | Left warning           |
| T03 | Right hazard  | Object right                  | Right warning          |
| T04 | Dog hazard    | Dog bark + nearby object      | Possible dog hazard    |
| T05 | Human voice   | Human voice + object          | No dog classification  |
| T06 | Vehicle sound | Traffic sound                 | No dog classification  |
| T07 | Crash         | Impact + abnormal orientation | Crash detected         |
| T08 | Cancel        | Cancel during countdown       | Alert cancelled        |
| T09 | Network loss  | Wi-Fi unavailable             | Local safety continues |
