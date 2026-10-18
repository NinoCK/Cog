package com.bestassistant.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonTest {

    @Test
    fun notesAreSaidBackToTheUser() {
        assertEquals("call your mom", Person.second("call my mom"))
        assertEquals("you have a meeting at 8 am", Person.second("I have a meeting at 8 am"))
        assertEquals("check the oven", Person.second("check the oven"))
    }
}
