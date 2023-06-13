package io.libp2p.simulate.util

fun <T1, T2, R> cartesianProduct(c1: Collection<T1>, c2: Collection<T2>, aggregator: (T1, T2) -> R): List<R> =
    c1.flatMap { t1 ->
        c2.map { t2 ->
            aggregator(t1, t2)
        }
    }

fun <T1, T2, R> cartesianProductT(c1: Collection<T1>, c2: Collection<T2>, aggregator: (Pair<T1, T2>) -> R): List<R> =
    cartesianProduct(c1, c2, ::Pair).map(aggregator)

fun <T1, T2, T3, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    aggregator: (T1, T2, T3) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.map { t3 ->
                aggregator(t1, t2, t3)
            }
        }
    }

fun <T1, T2, T3, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    aggregator: (Triple<T1, T2, T3>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, ::Triple).map(aggregator)

fun <T1, T2, T3, T4, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    aggregator: (T1, T2, T3, T4) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.map { t4 ->
                    aggregator(t1, t2, t3, t4)
                }
            }
        }
    }

fun <T1, T2, T3, T4, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    aggregator: (Tuple4<T1, T2, T3, T4>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, ::Tuple4).map(aggregator)

fun <T1, T2, T3, T4, T5, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    aggregator: (T1, T2, T3, T4, T5) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.map { t5 ->
                        aggregator(t1, t2, t3, t4, t5)
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    aggregator: (Tuple5<T1, T2, T3, T4, T5>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, ::Tuple5).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    aggregator: (T1, T2, T3, T4, T5, T6) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.map { t6 ->
                            aggregator(t1, t2, t3, t4, t5, t6)
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    aggregator: (Tuple6<T1, T2, T3, T4, T5, T6>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, ::Tuple6).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.map { t7 ->
                                aggregator(t1, t2, t3, t4, t5, t6, t7)
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    aggregator: (Tuple7<T1, T2, T3, T4, T5, T6, T7>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, ::Tuple7).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.map { t8 ->
                                    aggregator(t1, t2, t3, t4, t5, t6, t7, t8)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    aggregator: (Tuple8<T1, T2, T3, T4, T5, T6, T7, T8>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, ::Tuple8).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.map { t9 ->
                                        aggregator(t1, t2, t3, t4, t5, t6, t7, t8, t9)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    aggregator: (Tuple9<T1, T2, T3, T4, T5, T6, T7, T8, T9>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, ::Tuple9).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.flatMap { t9 ->
                                        c10.map { t10 ->
                                            aggregator(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    aggregator: (Tuple10<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, ::Tuple10).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.flatMap { t9 ->
                                        c10.flatMap { t10 ->
                                            c11.map { t11 ->
                                                aggregator(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    aggregator: (Tuple11<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, ::Tuple11).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.flatMap { t9 ->
                                        c10.flatMap { t10 ->
                                            c11.flatMap { t11 ->
                                                c12.map { t12 ->
                                                    aggregator(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    aggregator: (Tuple12<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, ::Tuple12).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.flatMap { t9 ->
                                        c10.flatMap { t10 ->
                                            c11.flatMap { t11 ->
                                                c12.flatMap { t12 ->
                                                    c13.map { t13 ->
                                                        aggregator(
                                                            t1,
                                                            t2,
                                                            t3,
                                                            t4,
                                                            t5,
                                                            t6,
                                                            t7,
                                                            t8,
                                                            t9,
                                                            t10,
                                                            t11,
                                                            t12,
                                                            t13
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    aggregator: (Tuple13<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, ::Tuple13).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    c14: Collection<T14>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.flatMap { t9 ->
                                        c10.flatMap { t10 ->
                                            c11.flatMap { t11 ->
                                                c12.flatMap { t12 ->
                                                    c13.flatMap { t13 ->
                                                        c14.map { t14 ->
                                                            aggregator(
                                                                t1,
                                                                t2,
                                                                t3,
                                                                t4,
                                                                t5,
                                                                t6,
                                                                t7,
                                                                t8,
                                                                t9,
                                                                t10,
                                                                t11,
                                                                t12,
                                                                t13,
                                                                t14
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    c14: Collection<T14>,
    aggregator: (Tuple14<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, ::Tuple14).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    c14: Collection<T14>,
    c15: Collection<T15>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.flatMap { t9 ->
                                        c10.flatMap { t10 ->
                                            c11.flatMap { t11 ->
                                                c12.flatMap { t12 ->
                                                    c13.flatMap { t13 ->
                                                        c14.flatMap { t14 ->
                                                            c15.map { t15 ->
                                                                aggregator(
                                                                    t1,
                                                                    t2,
                                                                    t3,
                                                                    t4,
                                                                    t5,
                                                                    t6,
                                                                    t7,
                                                                    t8,
                                                                    t9,
                                                                    t10,
                                                                    t11,
                                                                    t12,
                                                                    t13,
                                                                    t14,
                                                                    t15
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    c14: Collection<T14>,
    c15: Collection<T15>,
    aggregator: (Tuple15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, ::Tuple15).map(aggregator)

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> cartesianProduct(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    c14: Collection<T14>,
    c15: Collection<T15>,
    c16: Collection<T16>,
    aggregator: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16) -> R
): List<R> =
    c1.flatMap { t1 ->
        c2.flatMap { t2 ->
            c3.flatMap { t3 ->
                c4.flatMap { t4 ->
                    c5.flatMap { t5 ->
                        c6.flatMap { t6 ->
                            c7.flatMap { t7 ->
                                c8.flatMap { t8 ->
                                    c9.flatMap { t9 ->
                                        c10.flatMap { t10 ->
                                            c11.flatMap { t11 ->
                                                c12.flatMap { t12 ->
                                                    c13.flatMap { t13 ->
                                                        c14.flatMap { t14 ->
                                                            c15.flatMap { t15 ->
                                                                c16.map { t16 ->
                                                                    aggregator(
                                                                        t1,
                                                                        t2,
                                                                        t3,
                                                                        t4,
                                                                        t5,
                                                                        t6,
                                                                        t7,
                                                                        t8,
                                                                        t9,
                                                                        t10,
                                                                        t11,
                                                                        t12,
                                                                        t13,
                                                                        t14,
                                                                        t15,
                                                                        t16
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R> cartesianProductT(
    c1: Collection<T1>,
    c2: Collection<T2>,
    c3: Collection<T3>,
    c4: Collection<T4>,
    c5: Collection<T5>,
    c6: Collection<T6>,
    c7: Collection<T7>,
    c8: Collection<T8>,
    c9: Collection<T9>,
    c10: Collection<T10>,
    c11: Collection<T11>,
    c12: Collection<T12>,
    c13: Collection<T13>,
    c14: Collection<T14>,
    c15: Collection<T15>,
    c16: Collection<T16>,
    aggregator: (Tuple16<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16>) -> R
): List<R> =
    cartesianProduct(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, ::Tuple16).map(aggregator)

// src generator
fun main() {
    for (n in 4..16) {
        fun mapJoin(separator: String, mapper: (Int) -> Any): String =
            (1..n).map(mapper).joinToString(separator)

        println("""
            fun <${mapJoin(", ") { "T$it" }}, R> cartesianProduct(
            ${mapJoin(",\n") { "c$it: Collection<T$it>" }.prependIndent("    ")},
                aggregator: (${mapJoin(", ") { "T$it" }}) -> R
            ): List<R> =
                ${
            (1..n - 1)
                .map { "c$it.flatMap { t$it ->" }
                .joinToString("\n")
        }
                c$n.map { t$n ->
                        aggregator(${mapJoin(", ") { "t$it" }})
                ${mapJoin("\n") { "}" }} 
        """.trimIndent()
        )

        println(
            """
            fun <${mapJoin(", ") { "T$it" }}, R> cartesianProductT(
            ${mapJoin(",\n") { "c$it: Collection<T$it>" }.prependIndent("    ")},
                aggregator: (Tuple$n<${mapJoin(", ") { "T$it" }}>) -> R
            ): List<R> =
                cartesianProduct(${mapJoin(", ") { "c$it" }}, ::Tuple$n).map(aggregator)

        """.trimIndent()
        )
    }
}