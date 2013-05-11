/*
 *  @author Silvan Troxler
 *  
 *  Copyright 2013 University of Zurich
 *      
 *  Licensed below the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed below the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations below the License.
 *  
 */

/**
 * Helper function to sum up the individual elements of two arrays.
 * @param {Array.<number>} sum - The array to increase and return.
 * @param {Array.<number>} array - The array to get the numbers to sum up from.
 * @return {Array.<number>} - An array with summed up individual array elements.
 */
Array.sumElements = function(sum, array) {
  array.forEach(function(num, index) {
    if (sum[index] == undefined) {
      sum[index] = 0;
    }
    sum[index] = sum[index] + num;
  });
  return sum;
};

/**
 * Specific dataCallback function to some up the individual messageSent
 * statistics.
 * @param {Object} data - The data object that will be looked at.
 * @return {Array.<number>} - The summed array values.
 */
var sumMessageSent = function(data) {
  var statistics = [ "messagesSentToNodes",
                     "messagesSentToWorkers",
                     "messagesSentToCoordinator",
                     "messagesSentToOthers" ];
  var numValues = data.workerStatistics[statistics[0]].length;
  var sum = new Array(numValues);
  statistics.forEach(function(sentTo){
    sum = Array.sumElements(sum, data.workerStatistics[sentTo]);
  });
  return sum;
};

/**
 * Specific dataCallback function to some up the individual messageReceived
 * statistics.
 * @param {Object} data - The data object that will be looked at.
 * @return {Array.<number>} - The summed array values.
 */
var sumMessageReceived = function(data) {
  var statistics = [ "otherMessagesReceived",
                     "requestMessagesReceived",
                     "signalMessagesReceived",
                     "receiveTimeoutMessagesReceived",
                     "bulkSignalMessagesReceived",
                     "continueMessagesReceived",
                     "heartbeatMessagesReceived" ];
  var numValues = data.workerStatistics[statistics[0]].length;
  var sum = new Array(numValues);
  statistics.forEach(function(receivedFrom){
    sum = Array.sumElements(sum, data.workerStatistics[receivedFrom]);
  });
  return sum;
};

/**
 * Function that returns true when the passed DOM element is in the viewport,
 * false otherwise.
 * @param {Object} el - The element to check whether it is in the viewport.
 * @return {boolean} - Whether or not the passed DOM element is in the viewport.
 */ 
function isElementOverlappingViewport(el) {
  var rect = el.getBoundingClientRect();
  return (
      rect.top >= -600 &&
      rect.left >= 0 &&
      rect.bottom <= (window.innerHeight || document.documentElement.clientHeight) + 600 && /*or $(window).height() */
      rect.right <= (window.innerWidth || document.documentElement.clientWidth) /*or $(window).width() */
      );
}

/**
 * Returns whether or not a string ends with the passed suffix.
 * @param {string} suffix - The suffix to check the end of the string for.
 * @return {boolean} - Whether or not the string ends with suffix.
 */
String.prototype.endsWith = function(suffix) {
  return this.indexOf(suffix, this.length - suffix.length) !== -1;
};