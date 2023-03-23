Feature: Employees API

  Background:
    Given openapi ./employees-without-examples.yaml

  Scenario Outline: Create Employee
    When POST /westse/specmatic/employees
    Then status 201
    And RESPONSE-BODY (number)
    Examples:
      | (Employee) |
      | { "id": 100, "name": "Javier Doe", "department": {"name": "Engineering", "LOB": "LOB2"}, "designation": "Director" } |

  Scenario Outline: Get Employee Success
    When GET /westse/specmatic/employees/(id:number)
    Then status 200
    Examples:
      | id |
      | 10 |
      | 20 |

  Scenario Outline: Get Employee Not Found Error
    When GET /westse/specmatic/employees/100
    Then status 404

  Scenario Outline: Update Employee Success
    When PUT /westse/specmatic/employees/10
    Then status 200
    Examples:
      | (Employee) |
      | { "id": 10, "name": "Jill Hill Doe", "department": {"name": "Engineering", "LOB": "LOB2"}, "designation": "Director" } |
