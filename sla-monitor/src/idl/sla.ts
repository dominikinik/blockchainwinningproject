/**
 * Program IDL in camelCase format in order to be used in JS/TS.
 *
 * Note that this is only a type helper and is not the actual IDL. The original
 * IDL can be found at `target/idl/sla.json`.
 */
export type Sla = {
  "address": "4ACuzhWwVVqtbYgicWzq11BtowhsEEHLcChJn2gVR4R9",
  "metadata": {
    "name": "sla",
    "version": "0.1.0",
    "spec": "0.1.0",
    "description": "SLA escrow, monitor consensus and settlement"
  },
  "docs": [
    "Interface frozen in Phase 0; see SPEC.md. Handlers are stubs until T1."
  ],
  "instructions": [
    {
      "name": "createSla",
      "discriminator": [
        77,
        25,
        198,
        255,
        166,
        149,
        45,
        163
      ],
      "accounts": [
        {
          "name": "customer",
          "writable": true,
          "signer": true
        },
        {
          "name": "config",
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  99,
                  111,
                  110,
                  102,
                  105,
                  103
                ]
              }
            ]
          }
        },
        {
          "name": "sla",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  115,
                  108,
                  97
                ]
              },
              {
                "kind": "account",
                "path": "customer"
              },
              {
                "kind": "arg",
                "path": "slaId"
              }
            ]
          }
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": [
        {
          "name": "slaId",
          "type": {
            "array": [
              "u8",
              16
            ]
          }
        },
        {
          "name": "params",
          "type": {
            "defined": {
              "name": "createSlaParams"
            }
          }
        },
        {
          "name": "monitors",
          "type": {
            "vec": "pubkey"
          }
        }
      ]
    },
    {
      "name": "finalizeWindow",
      "discriminator": [
        183,
        76,
        248,
        161,
        196,
        14,
        74,
        46
      ],
      "accounts": [
        {
          "name": "sla",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  115,
                  108,
                  97
                ]
              },
              {
                "kind": "account",
                "path": "sla.customer",
                "account": "sla"
              },
              {
                "kind": "account",
                "path": "sla.slaId",
                "account": "sla"
              }
            ]
          }
        },
        {
          "name": "windowReport",
          "docs": [
            "reports. Either uninitialized (no monitor reported: the window counts 0 checks) or a",
            "`WindowReport` owned by this program, which the handler reads and closes to `payer`."
          ],
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  119,
                  105,
                  110,
                  100,
                  111,
                  119
                ]
              },
              {
                "kind": "account",
                "path": "sla"
              },
              {
                "kind": "arg",
                "path": "windowIndex"
              }
            ]
          }
        },
        {
          "name": "payer",
          "docs": [
            "report exists (`PayerMismatch`); otherwise any writable account (e.g. the caller)."
          ],
          "writable": true
        }
      ],
      "args": [
        {
          "name": "windowIndex",
          "type": "u32"
        }
      ]
    },
    {
      "name": "initializeConfig",
      "discriminator": [
        208,
        127,
        21,
        1,
        194,
        190,
        196,
        70
      ],
      "accounts": [
        {
          "name": "admin",
          "writable": true,
          "signer": true
        },
        {
          "name": "config",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  99,
                  111,
                  110,
                  102,
                  105,
                  103
                ]
              }
            ]
          }
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": [
        {
          "name": "params",
          "type": {
            "defined": {
              "name": "configParams"
            }
          }
        }
      ]
    },
    {
      "name": "registerMonitor",
      "discriminator": [
        187,
        221,
        199,
        209,
        121,
        191,
        83,
        160
      ],
      "accounts": [
        {
          "name": "admin",
          "writable": true,
          "signer": true,
          "relations": [
            "config"
          ]
        },
        {
          "name": "config",
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  99,
                  111,
                  110,
                  102,
                  105,
                  103
                ]
              }
            ]
          }
        },
        {
          "name": "authority"
        },
        {
          "name": "monitor",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  111,
                  110,
                  105,
                  116,
                  111,
                  114
                ]
              },
              {
                "kind": "account",
                "path": "authority"
              }
            ]
          }
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": [
        {
          "name": "name",
          "type": "string"
        }
      ]
    },
    {
      "name": "setMonitorActive",
      "discriminator": [
        141,
        8,
        114,
        72,
        42,
        177,
        172,
        178
      ],
      "accounts": [
        {
          "name": "admin",
          "signer": true,
          "relations": [
            "config"
          ]
        },
        {
          "name": "config",
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  99,
                  111,
                  110,
                  102,
                  105,
                  103
                ]
              }
            ]
          }
        },
        {
          "name": "monitor",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  111,
                  110,
                  105,
                  116,
                  111,
                  114
                ]
              },
              {
                "kind": "account",
                "path": "monitor.authority",
                "account": "monitor"
              }
            ]
          }
        }
      ],
      "args": [
        {
          "name": "active",
          "type": "bool"
        }
      ]
    },
    {
      "name": "settle",
      "discriminator": [
        175,
        42,
        185,
        87,
        144,
        131,
        102,
        212
      ],
      "accounts": [
        {
          "name": "sla",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  115,
                  108,
                  97
                ]
              },
              {
                "kind": "account",
                "path": "sla.customer",
                "account": "sla"
              },
              {
                "kind": "account",
                "path": "sla.slaId",
                "account": "sla"
              }
            ]
          }
        },
        {
          "name": "customer",
          "writable": true
        },
        {
          "name": "provider",
          "writable": true
        }
      ],
      "args": []
    },
    {
      "name": "submitReport",
      "discriminator": [
        27,
        178,
        64,
        9,
        20,
        46,
        250,
        14
      ],
      "accounts": [
        {
          "name": "monitorAuthority",
          "docs": [
            "Monitor wallet; must be in `sla.monitors`. Pays rent if this report creates the `WindowReport`."
          ],
          "writable": true,
          "signer": true
        },
        {
          "name": "monitor",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  109,
                  111,
                  110,
                  105,
                  116,
                  111,
                  114
                ]
              },
              {
                "kind": "account",
                "path": "monitorAuthority"
              }
            ]
          }
        },
        {
          "name": "sla",
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  115,
                  108,
                  97
                ]
              },
              {
                "kind": "account",
                "path": "sla.customer",
                "account": "sla"
              },
              {
                "kind": "account",
                "path": "sla.slaId",
                "account": "sla"
              }
            ]
          }
        },
        {
          "name": "windowReport",
          "writable": true,
          "pda": {
            "seeds": [
              {
                "kind": "const",
                "value": [
                  119,
                  105,
                  110,
                  100,
                  111,
                  119
                ]
              },
              {
                "kind": "account",
                "path": "sla"
              },
              {
                "kind": "arg",
                "path": "windowIndex"
              }
            ]
          }
        },
        {
          "name": "systemProgram",
          "address": "11111111111111111111111111111111"
        }
      ],
      "args": [
        {
          "name": "windowIndex",
          "type": "u32"
        },
        {
          "name": "checked",
          "type": {
            "array": [
              "u8",
              32
            ]
          }
        },
        {
          "name": "up",
          "type": {
            "array": [
              "u8",
              32
            ]
          }
        }
      ]
    }
  ],
  "accounts": [
    {
      "name": "config",
      "discriminator": [
        155,
        12,
        170,
        224,
        30,
        250,
        204,
        130
      ]
    },
    {
      "name": "monitor",
      "discriminator": [
        197,
        187,
        52,
        136,
        133,
        153,
        154,
        100
      ]
    },
    {
      "name": "sla",
      "discriminator": [
        93,
        177,
        43,
        102,
        221,
        228,
        221,
        169
      ]
    },
    {
      "name": "windowReport",
      "discriminator": [
        209,
        24,
        187,
        4,
        226,
        33,
        70,
        72
      ]
    }
  ],
  "events": [
    {
      "name": "reportSubmitted",
      "discriminator": [
        30,
        14,
        109,
        53,
        161,
        40,
        129,
        244
      ]
    },
    {
      "name": "slaCreated",
      "discriminator": [
        77,
        22,
        56,
        178,
        112,
        198,
        56,
        107
      ]
    },
    {
      "name": "slaSettled",
      "discriminator": [
        216,
        23,
        30,
        60,
        220,
        62,
        173,
        139
      ]
    },
    {
      "name": "windowFinalized",
      "discriminator": [
        88,
        205,
        50,
        2,
        189,
        54,
        157,
        192
      ]
    }
  ],
  "errors": [
    {
      "code": 6000,
      "name": "unauthorized",
      "msg": "Signer is not the config admin"
    },
    {
      "code": 6001,
      "name": "invalidConfig",
      "msg": "Config parameters are out of range"
    },
    {
      "code": 6002,
      "name": "invalidMonitorName",
      "msg": "Monitor name is empty or longer than 32 bytes"
    },
    {
      "code": 6003,
      "name": "invalidParams",
      "msg": "SLA parameters are invalid"
    },
    {
      "code": 6004,
      "name": "invalidSlaName",
      "msg": "SLA name is empty or longer than 64 bytes"
    },
    {
      "code": 6005,
      "name": "invalidEndpoint",
      "msg": "Endpoint is empty, longer than 200 bytes, or not https://"
    },
    {
      "code": 6006,
      "name": "zeroEscrow",
      "msg": "Escrow must be greater than zero"
    },
    {
      "code": 6007,
      "name": "invalidUptimeTarget",
      "msg": "Required uptime must be between 1 and 10000 basis points"
    },
    {
      "code": 6008,
      "name": "invalidDuration",
      "msg": "Duration must be positive, at most 90 days, and at most 2160 windows"
    },
    {
      "code": 6009,
      "name": "invalidCheckInterval",
      "msg": "Check interval must be at least 10s and fit at most 256 checks in a window"
    },
    {
      "code": 6010,
      "name": "invalidTimeout",
      "msg": "Timeout must be 100..=30000 ms and shorter than the check interval"
    },
    {
      "code": 6011,
      "name": "providerIsCustomer",
      "msg": "Provider must differ from the customer"
    },
    {
      "code": 6012,
      "name": "invalidMonitorCount",
      "msg": "Monitor list must have 1..=max_monitors_per_sla entries"
    },
    {
      "code": 6013,
      "name": "duplicateMonitor",
      "msg": "Monitor list contains a duplicate"
    },
    {
      "code": 6014,
      "name": "invalidConsensus",
      "msg": "Consensus must be between 1 and the number of monitors"
    },
    {
      "code": 6015,
      "name": "monitorAccountMismatch",
      "msg": "Remaining accounts do not match the monitor list"
    },
    {
      "code": 6016,
      "name": "monitorInactive",
      "msg": "Monitor is not active"
    },
    {
      "code": 6017,
      "name": "notAssignedMonitor",
      "msg": "Signer is not assigned to this SLA"
    },
    {
      "code": 6018,
      "name": "invalidWindowIndex",
      "msg": "Window index is outside the SLA"
    },
    {
      "code": 6019,
      "name": "windowNotEnded",
      "msg": "Window has not ended yet"
    },
    {
      "code": 6020,
      "name": "reportDeadlinePassed",
      "msg": "Report deadline for this window has passed"
    },
    {
      "code": 6021,
      "name": "duplicateReport",
      "msg": "Monitor already reported this window"
    },
    {
      "code": 6022,
      "name": "invalidBitmap",
      "msg": "Bitmap marks a slot up that was not checked, or a slot past the window's check count"
    },
    {
      "code": 6023,
      "name": "windowOutOfOrder",
      "msg": "Window must be finalized in order"
    },
    {
      "code": 6024,
      "name": "reportDeadlineNotPassed",
      "msg": "Report deadline for this window has not passed yet"
    },
    {
      "code": 6025,
      "name": "payerMismatch",
      "msg": "Window report payer does not match"
    },
    {
      "code": 6026,
      "name": "notYetSettleable",
      "msg": "SLA cannot be settled yet"
    },
    {
      "code": 6027,
      "name": "windowsNotFinalized",
      "msg": "Not every window is finalized"
    },
    {
      "code": 6028,
      "name": "alreadySettled",
      "msg": "SLA is already settled"
    },
    {
      "code": 6029,
      "name": "mathOverflow",
      "msg": "Arithmetic overflow"
    }
  ],
  "types": [
    {
      "name": "config",
      "docs": [
        "Global settings. PDA `[\"config\"]`. Written once by `initialize_config`."
      ],
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "admin",
            "type": "pubkey"
          },
          {
            "name": "windowSecs",
            "type": "u32"
          },
          {
            "name": "reportGraceSecs",
            "type": "u32"
          },
          {
            "name": "maxMonitorsPerSla",
            "type": "u8"
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "configParams",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "windowSecs",
            "docs": [
              "Window length; `DEFAULT_WINDOW_SECS` (3600) outside of tests and demos."
            ],
            "type": "u32"
          },
          {
            "name": "reportGraceSecs",
            "docs": [
              "How long after a window ends monitors may still report; default 600."
            ],
            "type": "u32"
          },
          {
            "name": "maxMonitorsPerSla",
            "docs": [
              "1..=MAX_MONITORS_PER_SLA; default 5."
            ],
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "createSlaParams",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "provider",
            "type": "pubkey"
          },
          {
            "name": "name",
            "type": "string"
          },
          {
            "name": "endpoint",
            "docs": [
              "https:// URL the monitors check."
            ],
            "type": "string"
          },
          {
            "name": "escrowLamports",
            "docs": [
              "Moved from the customer into the `Sla` account."
            ],
            "type": "u64"
          },
          {
            "name": "requiredUptimeBps",
            "docs": [
              "1..=10_000; 9_990 = 99.90%."
            ],
            "type": "u16"
          },
          {
            "name": "durationSecs",
            "docs": [
              "`end_ts = start_ts + duration_secs`, where `start_ts` is the clock at creation."
            ],
            "type": "u32"
          },
          {
            "name": "checkIntervalSecs",
            "type": "u32"
          },
          {
            "name": "timeoutMs",
            "type": "u32"
          },
          {
            "name": "consensusRequired",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "monitor",
      "docs": [
        "A registered monitor node. PDA `[\"monitor\", authority]`, where `authority` is the wallet the",
        "node signs reports with."
      ],
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "authority",
            "type": "pubkey"
          },
          {
            "name": "name",
            "type": "string"
          },
          {
            "name": "active",
            "type": "bool"
          },
          {
            "name": "reportsSubmitted",
            "type": "u64"
          },
          {
            "name": "slotsVoted",
            "docs": [
              "Check slots this monitor reported as checked, across finalized windows."
            ],
            "type": "u64"
          },
          {
            "name": "slotsAgreed",
            "docs": [
              "Of those, the counted slots where the monitor's up/down matched consensus."
            ],
            "type": "u64"
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "monitorReport",
      "docs": [
        "One monitor's observations for one window. Bit `j` of `checked`/`up` (byte `j / 8`,",
        "bit `j % 8`, LSB first) is check slot `j`, at `window_start + j * check_interval_secs`."
      ],
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "checked",
            "type": {
              "array": [
                "u8",
                32
              ]
            }
          },
          {
            "name": "up",
            "type": {
              "array": [
                "u8",
                32
              ]
            }
          },
          {
            "name": "submitted",
            "type": "bool"
          }
        ]
      }
    },
    {
      "name": "recipient",
      "type": {
        "kind": "enum",
        "variants": [
          {
            "name": "provider"
          },
          {
            "name": "customer"
          }
        ]
      }
    },
    {
      "name": "reportSubmitted",
      "docs": [
        "Emitted per accepted report. The frontend reads recent ones to show per-monitor observations."
      ],
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "sla",
            "type": "pubkey"
          },
          {
            "name": "windowIndex",
            "type": "u32"
          },
          {
            "name": "monitor",
            "docs": [
              "Monitor authority (wallet) that signed the report."
            ],
            "type": "pubkey"
          },
          {
            "name": "checked",
            "type": {
              "array": [
                "u8",
                32
              ]
            }
          },
          {
            "name": "up",
            "type": {
              "array": [
                "u8",
                32
              ]
            }
          },
          {
            "name": "timestamp",
            "type": "i64"
          }
        ]
      }
    },
    {
      "name": "sla",
      "docs": [
        "One service-level agreement. PDA `[\"sla\", customer, sla_id]`.",
        "The escrow is held as lamports in this account above its rent-exempt minimum.",
        "",
        "Field order is part of the contract: `customer` (offset 8), `provider` (40), and",
        "`monitors` (length at 88, entry `i` at 92 + 32*i) sit before any variable-length field so",
        "clients can filter `getProgramAccounts` with memcmp. Don't move them."
      ],
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "customer",
            "type": "pubkey"
          },
          {
            "name": "provider",
            "type": "pubkey"
          },
          {
            "name": "slaId",
            "type": {
              "array": [
                "u8",
                16
              ]
            }
          },
          {
            "name": "monitors",
            "docs": [
              "Monitor authorities (wallets), fixed for the SLA's lifetime. Report bitmaps are indexed",
              "by position in this list."
            ],
            "type": {
              "vec": "pubkey"
            }
          },
          {
            "name": "name",
            "type": "string"
          },
          {
            "name": "endpoint",
            "type": "string"
          },
          {
            "name": "escrowLamports",
            "type": "u64"
          },
          {
            "name": "requiredUptimeBps",
            "docs": [
              "9_990 = 99.90%."
            ],
            "type": "u16"
          },
          {
            "name": "startTs",
            "type": "i64"
          },
          {
            "name": "endTs",
            "type": "i64"
          },
          {
            "name": "checkIntervalSecs",
            "type": "u32"
          },
          {
            "name": "timeoutMs",
            "type": "u32"
          },
          {
            "name": "consensusRequired",
            "type": "u8"
          },
          {
            "name": "windowSecs",
            "docs": [
              "Copied from `Config` at creation so the schedule never changes under a live SLA."
            ],
            "type": "u32"
          },
          {
            "name": "reportGraceSecs",
            "type": "u32"
          },
          {
            "name": "totalWindows",
            "type": "u32"
          },
          {
            "name": "nextWindowToFinalize",
            "type": "u32"
          },
          {
            "name": "upChecks",
            "type": "u64"
          },
          {
            "name": "countedChecks",
            "type": "u64"
          },
          {
            "name": "windowResults",
            "docs": [
              "One entry per finalized window, in order. The account is sized for `total_windows` entries."
            ],
            "type": {
              "vec": {
                "defined": {
                  "name": "windowResult"
                }
              }
            }
          },
          {
            "name": "settled",
            "type": "bool"
          },
          {
            "name": "recipient",
            "type": {
              "option": {
                "defined": {
                  "name": "recipient"
                }
              }
            }
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "slaCreated",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "sla",
            "type": "pubkey"
          },
          {
            "name": "customer",
            "type": "pubkey"
          },
          {
            "name": "provider",
            "type": "pubkey"
          },
          {
            "name": "slaId",
            "type": {
              "array": [
                "u8",
                16
              ]
            }
          },
          {
            "name": "escrowLamports",
            "type": "u64"
          },
          {
            "name": "requiredUptimeBps",
            "type": "u16"
          },
          {
            "name": "startTs",
            "type": "i64"
          },
          {
            "name": "endTs",
            "type": "i64"
          },
          {
            "name": "totalWindows",
            "type": "u32"
          },
          {
            "name": "monitors",
            "type": {
              "vec": "pubkey"
            }
          }
        ]
      }
    },
    {
      "name": "slaSettled",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "sla",
            "type": "pubkey"
          },
          {
            "name": "recipient",
            "type": {
              "defined": {
                "name": "recipient"
              }
            }
          },
          {
            "name": "amountLamports",
            "type": "u64"
          },
          {
            "name": "upChecks",
            "type": "u64"
          },
          {
            "name": "countedChecks",
            "type": "u64"
          }
        ]
      }
    },
    {
      "name": "windowFinalized",
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "sla",
            "type": "pubkey"
          },
          {
            "name": "windowIndex",
            "type": "u32"
          },
          {
            "name": "up",
            "type": "u16"
          },
          {
            "name": "counted",
            "type": "u16"
          },
          {
            "name": "hadReports",
            "docs": [
              "False when no `WindowReport` existed (the window counted 0 checks)."
            ],
            "type": "bool"
          }
        ]
      }
    },
    {
      "name": "windowReport",
      "docs": [
        "Reports for one window of one SLA. PDA `[\"window\", sla, window_index (u32 LE)]`.",
        "Created by the first report, closed by `finalize_window` (rent refunded to `payer`)."
      ],
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "sla",
            "type": "pubkey"
          },
          {
            "name": "windowIndex",
            "type": "u32"
          },
          {
            "name": "perMonitor",
            "docs": [
              "Indexed like `Sla::monitors`; entries past `monitors.len()` stay empty.",
              "Length is `MAX_MONITORS_PER_SLA` (a literal so the IDL gets a fixed array)."
            ],
            "type": {
              "array": [
                {
                  "defined": {
                    "name": "monitorReport"
                  }
                },
                5
              ]
            }
          },
          {
            "name": "payer",
            "docs": [
              "Whoever paid rent at creation (the first reporting monitor)."
            ],
            "type": "pubkey"
          },
          {
            "name": "bump",
            "type": "u8"
          }
        ]
      }
    },
    {
      "name": "windowResult",
      "docs": [
        "Consensus result of one finalized window."
      ],
      "type": {
        "kind": "struct",
        "fields": [
          {
            "name": "up",
            "docs": [
              "Slots decided UP."
            ],
            "type": "u16"
          },
          {
            "name": "counted",
            "docs": [
              "Slots decided at all (UP or DOWN). Slots with fewer than `consensus_required` votes are not counted."
            ],
            "type": "u16"
          }
        ]
      }
    }
  ],
  "constants": [
    {
      "name": "configSeed",
      "type": "bytes",
      "value": "[99, 111, 110, 102, 105, 103]"
    },
    {
      "name": "defaultReportGraceSecs",
      "type": "u32",
      "value": "600"
    },
    {
      "name": "defaultWindowSecs",
      "docs": [
        "Defaults the admin passes to `initialize_config` outside of tests."
      ],
      "type": "u32",
      "value": "3600"
    },
    {
      "name": "maxChecksPerWindow",
      "docs": [
        "One bit per check slot in a window report bitmap."
      ],
      "type": "u32",
      "value": "256"
    },
    {
      "name": "maxEndpointLen",
      "type": "u32",
      "value": "200"
    },
    {
      "name": "maxMonitorsPerSla",
      "docs": [
        "Hard cap on monitors per SLA; `Config::max_monitors_per_sla` may be lower, never higher."
      ],
      "type": "u8",
      "value": "5"
    },
    {
      "name": "maxMonitorNameLen",
      "type": "u32",
      "value": "32"
    },
    {
      "name": "maxSlaDurationSecs",
      "type": "u32",
      "value": "7776000"
    },
    {
      "name": "maxSlaNameLen",
      "type": "u32",
      "value": "64"
    },
    {
      "name": "maxTimeoutMs",
      "type": "u32",
      "value": "30000"
    },
    {
      "name": "maxUptimeBps",
      "docs": [
        "10_000 basis points = 100.00% uptime."
      ],
      "type": "u16",
      "value": "10000"
    },
    {
      "name": "maxWindows",
      "docs": [
        "90 days of 1-hour windows. Also caps SLAs under a shorter `window_secs`."
      ],
      "type": "u32",
      "value": "2160"
    },
    {
      "name": "minCheckIntervalSecs",
      "type": "u32",
      "value": "10"
    },
    {
      "name": "minTimeoutMs",
      "type": "u32",
      "value": "100"
    },
    {
      "name": "monitorSeed",
      "type": "bytes",
      "value": "[109, 111, 110, 105, 116, 111, 114]"
    },
    {
      "name": "slaSeed",
      "type": "bytes",
      "value": "[115, 108, 97]"
    },
    {
      "name": "windowSeed",
      "type": "bytes",
      "value": "[119, 105, 110, 100, 111, 119]"
    }
  ]
};
